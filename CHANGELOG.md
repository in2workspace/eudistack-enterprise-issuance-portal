# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
