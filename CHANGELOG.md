# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **EUD-275 — servicio, recorrido y sesión**: servicio nuevo en Java 25 y Spring MVC sobre hilos virtuales, con un paquete por módulo (`journey`, `identification`, `authenticsource`) y la sesión compartida (`session`). Las pruebas de arquitectura impiden que un módulo use a otro fuera de su paquete `api`.
- Tenant resuelto a partir del host (o de `X-Tenant` en dominios propios) y comprobado en `public.tenant_registry`; un tenant desconocido responde 404 `tenant_not_found`. Esquema por tenant `<tenant>_issuance_portal`, migrado con Flyway al arrancar.
- `POST /journey`, `GET /journey` y `POST /journey/withdrawal` bajo `/issuance-portal/api/v1`, con la cookie `journey_session` (`HttpOnly`, `Secure`, `SameSite=Lax`, ruta `/issuance-portal`). Empezar un recorrido retira el anterior del navegador; retirar un recorrido completado solo cierra su sesión.
- Sesión del recorrido con una sección por módulo, datos personales cifrados por sobre con AES-256-GCM (clave de datos de KMS o clave local) y solo el hash del identificador de sesión en la base de datos. Puertos publicados para los otros módulos: `JourneyAccess`, `JourneyProgress` y `JourneyWithdrawal`.
- Caducidad por inactividad (15 minutos) en cada petición y en un barrido cada minuto con bloqueo ShedLock por tenant.
- Traza de cada cambio del recorrido sin datos personales, de momento como log estructurado (el envío a la API de auditoría está pendiente).
- Errores como Problem (RFC 9457), cabecera `X-Requested-With: issuance-portal` obligatoria en las peticiones que cambian estado, logs JSON con el contrato de observabilidad, métricas por tenant e imagen Docker con sus pipelines de CI.
