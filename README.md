# eudistack-enterprise-issuance-portal

Service behind the EUDIStack issuance portal. It runs the holder's journey, identifies the holder and
checks the tenant's authentic source, sharing one server-side journey session between its three
modules: Journey, Identification and Authentic source.

It replaces the demo Node server of `eudistack-enterprise-cert-identifier-service`.

## Stack

Java 25, Spring Boot 3.5, Spring MVC on virtual threads, PostgreSQL (schema per tenant), hexagonal
architecture with one package per module.

## Commands

| Task | Command |
|------|---------|
| Build, test, checkstyle, coverage | `./gradlew build` |
| Tests only | `./gradlew test` |
| Run with the rest of the platform | `make up` in `eudistack-platform-dev` |

The API is served under `/issuance-portal/api/v1`. The functional specification, the design and the
story breakdown live in `eudistack-platform-dev`, under `docs/features/EUD-262-servicio-portal-emision/`.
