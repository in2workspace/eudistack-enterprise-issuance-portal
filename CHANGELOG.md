# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **EUD-358 — verificación del correo del titular con un código**:
  - `POST /journey/email-verification` envía al correo un código de 6 dígitos (10 minutos de validez y 5 intentos). Un código nuevo anula el anterior y deja de dar por verificado el correo anterior.
  - Límites: 3 envíos por recorrido, 180 s entre envíos (429 `resend_too_soon` con `Retry-After`) y 10 por IP y hora (429 `too_many_requests`). Con 3 envíos, 429 `too_many_codes`.
  - Un envío que no sale responde 503 `email_unavailable`, no se guarda y no cuenta en los límites.
  - `POST /journey/email-verification/code` comprueba el código (400 `invalid_code`, `expired_code` o `too_many_attempts`) y, si es correcto, fija el correo verificado y pasa al paso `email_verified`.
  - Correo por SMTP en español o inglés según `Accept-Language`, con el nombre del portal de `texts.<idioma>.portal.app_name` y sin enlaces. Es la plantilla del correo OTP del wallet (HTML con la marca del tenant, sin ningún enlace ni fuente externa: no carga Google Fonts) con el texto plano como alternativa (`multipart/alternative`). El logo y el fondo de cabecera salen solo si el bloque del tenant en `email-branding.yaml` los activa, desde `EMAIL_ASSETS_BASE_URL/<tenant>/email/`; `EMAIL_BRANDING_PATH` sustituye el fichero, como en el wallet. Con `smtp`, `EMAIL_ASSETS_BASE_URL` es obligatoria. El adaptador local no envía ni registra el código.
  - El código se guarda como SHA-256 del recorrido y el código, en la sección cifrada nueva `email_verification`. La migración V4 añade su columna y el paso `email_verified`.
  - `GET /journey` devuelve `email_verification`. Nuevas trazas del envío y de la comprobación, sin el correo ni el código.

- **EUD-277 — emisión como sistema: confirmación, token de sistema y ticket de alta (fase 1)**: `POST /journey/confirmation` bajo `/issuance-portal/api/v1`. Solo lee `email` (obligatorio y validado: 400 `email_required` o `email_invalid`); exige la identidad y el derecho confirmado (409 `step_not_allowed`) y la configuración del tenant (503 `config_unavailable`). Arma el `payload` con `data_placement` desde la identidad y la fuente, pide la emisión con `delivery` `ui` y `grant_type` `authorization_code`, y responde 200 con `credential_offer_uri`, `expires_at` y, con alta con ticket, `onboarding_url` y `onboarding_expires_at`. El recorrido queda `completed` en `offer_obtained`, con un único `issuance_process_id`, que se guarda con su propio `UPDATE` condicionado (`recordIssuanceProcess`): el `update` genérico del recorrido ya no lo escribe, así que una lectura anterior sin bloqueo no puede borrarlo.
- Una clave de idempotencia por recorrido, generada en la primera confirmación: todos los reintentos, también los que pide la interfaz tras un error, la reutilizan. Desde EUD-358 se guarda con el correo verificado con el que se creó y se cambia por una nueva si el titular verifica otro correo antes de que exista el proceso de emisión. La fila del recorrido se bloquea (`SELECT ... FOR UPDATE`) solo para guardar la clave y para guardar el proceso; la llamada al Issuer va fuera de la transacción. Migración `V3`: `journey.issuance_process_id` sustituye a `issuance_process_ids`.
- Token de sistema por tenant (design 1.4.0, AD-5): la LEARCredentialMachine del portal y su clave se leen de SSM Parameter Store (`ssm`) o de un fichero montado (`file`); la presentación y la `client_assertion` se arman con `eudistack-verifier-client-java`, que rechaza una clave que no corresponde al `cnf`. El token se pide al Verifier por la red interna con `X-Tenant`, `X-Forwarded-Host` del tenant y `X-Forwarded-Proto: https`, se reutiliza hasta 30 s antes de caducar y un 401 del Wallet Unit Service lo invalida.
- Cliente de la API de emisión: el token de sistema, nunca el del titular; hasta 3 reintentos con la misma clave ante error de red y, si se activa `PORTAL_ISSUANCE_RETRY_ON_503`, ante 503, respetando `Retry-After` hasta 10 s (o 1, 2 y 4 s), con 10 s por llamada. Un 4xx o una respuesta sin `Location` o sin oferta da 502 `issuance_rejected`; sin token o sin respuesta, 503 `issuance_unavailable`. La oferta caduca a los 10 minutos de la respuesta.
- Resiliencia del cliente de la API de emisión (SEC-25, SEC-27): esperas de reintento exponenciales con variación aleatoria de hasta un 50 %, y por tenant un `Bulkhead` que limita las emisiones en curso y un cortacircuitos que se abre con errores de red, tiempos agotados y 5xx (nunca con un 4xx). Con cualquiera de los dos activo se responde 503 `issuance_unavailable` sin llamar al Issuer. Propiedades nuevas bajo `issuance-portal.issuance-api`: `max-concurrent-per-tenant` (`PORTAL_ISSUANCE_MAX_CONCURRENT_PER_TENANT`, 20), `circuit-failure-rate-percent` (50), `circuit-minimum-calls` (10) y `circuit-wait-in-open-state` (30 s).
- Ticket de alta en el Wallet Unit Service (`POST {wallet_unit_service_url}/onboarding-tickets`) cuando el tenant tiene `onboarding.enabled`, con `expiresIn` igual a lo que le queda a la oferta menos 60 s, 5 s de espera y sin reintentos. Si falla, 503 `onboarding_unavailable` sin oferta: se guarda el proceso y el recorrido sigue en `entitlement_confirmed`. En esta fase, una confirmación con el proceso ya guardado responde 503 `onboarding_unavailable` sin volver a emitir.
- Traza de la petición de emisión (`issued`, `rejected`, `unavailable`, `system_token_unavailable`, `onboarding_failed`, con el proceso cuando lo hay) y del ticket (`created`, `failed`), sin datos personales, ofertas, direcciones ni tokens.
- `GET /journey` devuelve `attestation` (la vista previa que guardó la fuente auténtica: `type_name`, `fields`, `email` y `email_required`) y `offer` (`credential_offer_uri`, `expires_at` y, con alta con ticket, `onboarding_url` y `onboarding_expires_at`), como dice el contrato. La oferta se devuelve aunque haya caducado. En un tenant con alta con ticket, una oferta sin dirección de alta no se devuelve. Una sección que no se puede leer se omite con un aviso en el log, sin datos personales. `POST /journey` y `POST /journey/withdrawal` no cambian (ampliación de EUD-277, decisión 8).
- Configuración nueva: `PORTAL_SYSTEM_TOKEN_PROVIDER`, `PORTAL_SYSTEM_TOKEN_PARAMETER_TEMPLATE`, `PORTAL_SYSTEM_TOKEN_DIRECTORY`, `PORTAL_VERIFIER_INTERNAL_URL`, `PORTAL_ISSUER_INTERNAL_URL`, `PORTAL_PUBLIC_HOST_TEMPLATE` y `PORTAL_ISSUANCE_RETRY_ON_503`. Sin las obligatorias el servicio no arranca.

- **EUD-281 — módulo de fuente auténtica con adaptador simulado**: `POST /entitlement` bajo `/issuance-portal/api/v1`, sin cuerpo: cualquier dato que envíe la interfaz se ignora. Toma el identificador personal y su tipo (`dni` o `colegiado`) solo de la identidad del recorrido y busca en la fuente siempre por ese tipo. Responde `confirmed` con la vista previa del paso Datos, o `not_confirmed` con el motivo (`not_found`, `not_entitled` o `identifier_mismatch`).
- Contratos publicados en `session.api` para las secciones `identity` (nombre, apellidos, identificador personal con su tipo y correo opcional del Access Token) y `entitlement` (resultado confirmado, tipo de identificador, datos de la atestación, vista previa y momento), con su forma JSON. Un contenido no válido nombra el campo, nunca su valor.
- Reglas de la comprobación: sin fuente para el tipo de atestación, `confirmed` solo con la identidad; persona no encontrada, `not_found`; identificador del tipo buscado distinto o ausente, `identifier_mismatch` (sin distinguir mayúsculas ni espacios); sin derecho, `not_entitled`. Con `not_confirmed` el recorrido se retira con `not_entitled` y sus datos se borran. Con `confirmed`, la sección `entitlement` y el paso `entitlement_confirmed` se guardan en la misma transacción.
- La fuente se consulta en un hilo virtual, sin transacción abierta y con una espera máxima de 10 s. Si falla, no responde o tarda, 503 `authentic_source_unavailable` con `Retry-After: 30`, y el recorrido sigue en curso.
- Sección `authentic_source` de `portal_config`, una entrada por tipo de atestación: adaptador, dirección, referencia al secreto, espera (1 a 10 s), tipos de identificador por los que busca, traducción de atributos a claims con su etiqueta y ajustes del adaptador. Antes de consultar se comprueba que el adaptador existe en el entorno, que admite esos tipos y que cubren los que puede dar el login del tenant. Si no, 503 `config_unavailable`.
- Registro de adaptadores por nombre, adaptado de `eudistack-enterprise-backend`. Un nombre repetido impide arrancar. Con `PORTAL_ENV=pro` el adaptador simulado no se registra.
- Adaptador `simulated` con registros sintéticos en la configuración del tenant. Cada registro se encuentra por DNI o NIE y por número de colegiado, y puede tener un retardo o un fallo forzado.
- Traza de cada comprobación (`audit.authentic_source`: recorrido, tenant, tipo de atestación, adaptador, tipo de identificador, resultado, motivo, duración y momento), sin identificador personal ni datos de la fuente.
- `JourneyAccess.requireInProgress`: un recorrido completado responde 410 `journey_closed` antes de consultar la fuente.

### Changed

- **EUD-358**: `POST /journey/confirmation` ya no lee el cuerpo. Usa el correo verificado del recorrido y, sin él, responde 409 `step_not_allowed`. La emisión y el ticket de alta llevan siempre ese correo, y desaparecen `email_required` y `email_invalid` de la confirmación.
- **EUD-358**: el límite por IP de las incidencias (5 por hora) es ahora una instancia configurable, separada de la del límite de los códigos del correo (10 por hora): cada una tiene su propio presupuesto.
- **EUD-358**: la confirmación exige la sección `email_verification` verificada y con el mismo correo que la sección `email`; una sección `email` sola, sin verificar o con otro correo da 409 `step_not_allowed` y no emite. La migración V4 vacía los correos que había en `journey_session.email`, porque ninguno estaba verificado.
- **EUD-358**: la clave de idempotencia de la emisión se guarda con el correo verificado. Si el titular verifica otro correo antes de que exista el proceso de emisión, la confirmación genera una clave nueva para ese correo (también si el registro es anterior y no guarda el correo). Con el proceso ya creado se mantienen la clave y el proceso (hasta EUD-295 esa confirmación responde 503 `onboarding_unavailable` sin volver a emitir).
- **EUD-358**: el correo (el código de verificación y las incidencias) se envía por el SMTP de SES con `spring.mail`, con las mismas variables `SMTP_*` que el wallet (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_SSL_ENABLE` y los tiempos `SMTP_*_TIMEOUT_MS`, 5 s por defecto). `PORTAL_MAIL_PROVIDER` pasa a valer `smtp` o `local` (sigue siendo obligatoria). Desaparecen el adaptador de la API de SES, la dependencia `sesv2` y `PORTAL_MAIL_FROM`: el remitente es `SMTP_FROM`. `SMTP_FROM` es obligatoria con `smtp` (sin ella el servicio no arranca). El portal exige STARTTLS (`starttls.required`) y valida el certificado del servidor con el truststore del JDK (`ssl.checkserveridentity`): sin TLS no se envían ni las credenciales ni el correo, y `SMTP_SSL_TRUST` no se usa. La comprobación de salud del correo de Spring Boot queda desactivada.

- Health y métricas en la raíz del servicio, como en el resto de servicios de la plataforma (`management.endpoints.web.base-path: /`): `/issuance-portal/health`, `/issuance-portal/health/liveness`, `/issuance-portal/health/readiness` y `/issuance-portal/prometheus`, en lugar de `/issuance-portal/actuator/...`. Los filtros de la cabecera `X-Requested-With` y del tenant dejan pasar solo esas rutas.

### Fixed

Revisión de la orden:

- El adaptador simulado solo se registra con `PORTAL_ENV` `local`, `test`, `dev` o `stg` (DEV usa `dev` en la IaC). Con cualquier otro valor (`pro`, `PRO`, `prod`, vacío…) queda fuera, aunque un tenant lo configure.
- Si el tipo de identificador de la identidad no está entre los que admite la fuente del tenant, 503 `config_unavailable` sin consultarla. El adaptador simulado comprueba que la entrada del tenant sigue eligiéndolo.
- Como mucho 100 búsquedas en la fuente a la vez por instancia (`PORTAL_AUTHENTIC_SOURCE_MAX_LOOKUPS`) y 20 por tenant (`PORTAL_AUTHENTIC_SOURCE_MAX_LOOKUPS_PER_TENANT`), para que la fuente lenta de un tenant no deje sin búsquedas a los demás. Por encima, 503 `authentic_source_unavailable` sin esperar. Un valor menor que 1 impide arrancar. Una búsqueda cancelada antes de empezar (por la espera máxima o por una petición interrumpida) devuelve su plaza.
- Una entrada de `authentic_source` con una clave distinta de `attestation_type` (por ejemplo, una errata) es una configuración no válida (503 `config_unavailable`, con el campo en el log). Antes dejaba el tipo sin fuente y se confirmaba a cualquier titular. Cada confirmación sin fuente deja un aviso en el log con el tenant y el tipo de atestación.
- Un elemento vacío en la traducción de la configuración (`"mapping": [null]`) es una configuración no válida (503 `config_unavailable`, con el campo en el log), no un error 500.
- La respuesta de `POST /entitlement` y los documentos JSON de la configuración y de las secciones `identity` y `entitlement` no muestran datos personales, direcciones ni secretos en su representación de texto.

## [0.2.0] - 2026-10-27

### Added

- **EUD-276 — configuración del portal e incidencias**: tabla `portal_config` en el esquema de cada tenant (migración `V2`, sin datos), con la configuración del portal en JSON: la parte pública del contrato (`texts`, `legal_links`, `support`, `wallet_url`) y la interna del servicio (tipo de atestación, colocación de datos, alta con ticket, `client_id` del portal y tipo de identificador por tipo de atestación). Se valida al leerla: una configuración incompleta, con `wallet_url` o un enlace legal que no son URL `https`, se trata como ausente.
- `GET /config`, sin token ni cookie, con solo la parte pública. Caché de 5 minutos por tenant (`issuance-portal.portal-config.cache-ttl`) que no guarda la ausencia ni la configuración no válida. Sin configuración válida, o si el tenant aún no tiene tabla, responde 503 `config_unavailable`.
- `POST /incidents`, sin token ni cookie: valida los campos del contrato (400 `bad_request`, con solo los nombres de los campos en `detail`), envía la incidencia en texto plano a `support.email` del tenant con el correo del titular en `Reply-To` y responde 202 con `response_time`. No guarda la incidencia. Sin dirección de soporte o plazo, o si el envío falla, 503 `service_unavailable`.
- Envío por Amazon SES (`PORTAL_MAIL_PROVIDER=ses`) o adaptador local que no envía (`local`), con el remitente en `PORTAL_MAIL_FROM`. Las dos variables son obligatorias.
- Límite de 5 incidencias en cualquier hora por cliente, con una ventana deslizante sobre las últimas incidencias aceptadas (una IPv6 cuenta por su /64), comprobado después de validar el cuerpo; un envío que falla no cuenta. Al superarlo, 429 `too_many_requests` con `Retry-After`, un log `rate.limit.exceeded` (uno por cliente y hora, con su IP: única excepción a «sin datos personales en los logs») y el contador `issuance_portal.incidents.rate_limited` por tenant. El límite es por instancia.
- El correo de la incidencia se limita a 254 caracteres ASCII con un formato estricto, porque va en el `Reply-To`. El asunto y los campos del cuerpo quedan en una sola línea, sin separadores de línea Unicode ni caracteres invisibles de formato.
- Un tenant dado de alta con el servicio en marcha obtiene su esquema en su primera petición, sin reiniciar ni desplegar.
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
