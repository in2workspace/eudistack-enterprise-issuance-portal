# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-10-27

### Added

- **EUD-276 — configuración del portal e incidencias**: tabla `portal_config` en el esquema de cada tenant (migración `V2`, sin datos), con la configuración del portal en JSON: la parte pública del contrato (`texts`, `legal_links`, `support`, `wallet_url`) y la interna del servicio (tipo de atestación, colocación de datos, alta con ticket, `client_id` del portal y tipo de identificador por tipo de atestación). Se valida al leerla: una configuración incompleta o con `wallet_url` que no es una URL `https` se trata como ausente.
- `GET /config`, sin token ni cookie, con solo la parte pública. Caché de 5 minutos por tenant (`issuance-portal.portal-config.cache-ttl`) que no guarda la ausencia ni la configuración no válida. Sin configuración válida responde 503 `config_unavailable`.
- `POST /incidents`, sin token ni cookie: valida los campos del contrato (400 `bad_request`, con solo los nombres de los campos en `detail`), envía la incidencia en texto plano a `support.email` del tenant con el correo del titular en `Reply-To` y responde 202 con `response_time`. No guarda la incidencia. Sin dirección de soporte o plazo, o si el envío falla, 503 `service_unavailable`.
- Envío por Amazon SES (`PORTAL_MAIL_PROVIDER=ses`) o adaptador local que no envía (`local`), con el remitente en `PORTAL_MAIL_FROM`. Las dos variables son obligatorias.
- Límite de 5 incidencias en cualquier hora por cliente, con una ventana deslizante sobre las últimas incidencias aceptadas (una IPv6 cuenta por su /64), comprobado después de validar el cuerpo. Al superarlo, 429 `too_many_requests` con `Retry-After`, un log `rate.limit.exceeded` y el contador `issuance_portal.incidents.rate_limited` por tenant. El límite es por instancia.
- El correo de la incidencia se limita a 254 caracteres ASCII con un formato estricto, porque va en el `Reply-To`.
- Traza de cada intento de envío (`audit.incident`: tenant, tipo, resultado y motivo del fallo), sin el contenido de la incidencia.
- La IP del cliente se toma de `X-Forwarded-For` contando los proxies de la plataforma desde la derecha (`PORTAL_TRUSTED_PROXIES`: 1 detrás de nginx, 2 detrás de CloudFront y el ALB, obligatoria), nunca de lo que escribe el cliente; solo se acepta una IP literal, en forma canónica. Se ignora la cabecera `Forwarded`.

## [0.1.0] - 2026-10-19

### Added

- **EUD-275 — servicio, recorrido y sesión**: servicio nuevo en Java 25 y Spring MVC sobre hilos virtuales, con un paquete por módulo (`journey`, `identification`, `authenticsource`) y la sesión compartida (`session`). Las pruebas de arquitectura impiden que un módulo use a otro fuera de su paquete `api`.
- Tenant resuelto a partir del host (o de `X-Tenant` en dominios propios) y comprobado en `public.tenant_registry`; un tenant desconocido responde 404 `tenant_not_found`. Esquema por tenant `<tenant>_issuance_portal`, migrado con Flyway al arrancar.
- `POST /journey`, `GET /journey` y `POST /journey/withdrawal` bajo `/issuance-portal/api/v1`, con la cookie `journey_session` (`HttpOnly`, `Secure`, `SameSite=Lax`, ruta `/issuance-portal`). Empezar un recorrido retira el anterior del navegador; retirar un recorrido completado solo cierra su sesión.
- Sesión del recorrido con una sección por módulo, datos personales cifrados por sobre con AES-256-GCM (clave de datos de KMS o clave local) y solo el hash del identificador de sesión en la base de datos. Puertos publicados para los otros módulos: `JourneyAccess`, `JourneyProgress` y `JourneyWithdrawal`.
- Caducidad por inactividad (15 minutos) en cada petición y en un barrido cada minuto con bloqueo ShedLock por tenant.
- Traza de cada cambio del recorrido sin datos personales, de momento como log estructurado (el envío a la API de auditoría está pendiente).
- Con PostgreSQL caído, todas las rutas responden 503 `service_unavailable` (también desde el filtro de tenant y al abrir la transacción), con un timeout de conexión de 3 s.
- Los filtros de la cabecera `X-Requested-With` y del tenant se aplican a todo salvo `/actuator`, sobre la ruta normalizada.
- Errores como Problem (RFC 9457), cabecera `X-Requested-With: issuance-portal` obligatoria en las peticiones que cambian estado, logs JSON con el contrato de observabilidad, métricas por tenant e imagen Docker con sus pipelines de CI.

### Fixed

Revisión de la PR #1:

- Las cabeceras de seguridad se envían en todas las respuestas, también en los errores.
- Al acceder a un recorrido caducado o retirado, el cierre se guarda en su propia transacción y no se deshace con el 410. Un recorrido anterior ya inactivo se retira como `expired`, no como `cancelled`.
- El barrido lee los candidatos por páginas y retira cada uno en su propia transacción, solo si sigue inactivo; la sesión se borra con la misma condición. Cubre también los tenants desactivados que aún tienen esquema.
- El servicio no arranca con la clave local fuera de `PORTAL_ENV=local` o `test`, ni en ECS. `PORTAL_ENV` es obligatorio.
- Las claves de datos de KMS en caché caducan por tiempo desde que se obtienen, no desde el último uso.
- Las pruebas de arquitectura impiden que el dominio dependa de algo que no sea Java o el propio dominio, y que la aplicación dependa de la infraestructura.
