# eudistack-enterprise-issuance-portal — repo guide

Service behind the issuance portal: the holder's journey, holder identification and the tenant's
authentic source, in one deployment with three modules that share the journey session.

Design: `docs/features/EUD-262-servicio-portal-emision/design.md` in `eudistack-platform-dev`.

## Invariants

- One package per module (`journey`, `identification`, `authenticsource`) plus `session` and `shared`.
  Modules never call each other except through the ports of `session` and the journey withdrawal port.
  Enforced by the architecture tests.
- Hexagonal inside each module: `domain/` depends on nothing outside itself.
- Personal data lives only in `journey_session`, encrypted, and is deleted when the journey leaves
  `in_progress`. Never in `journey`, logs or audit events.
- Tenant comes from the host, is resolved in a filter and never reaches the domain.
- Time is injected through the `Clock` bean, never read statically.
- Constructor injection only; no field `@Autowired`.
- WebMvc on virtual threads, never WebFlux (`AD-1`).

## Commands

| Task | Command |
|------|---------|
| Build, test, checkstyle, coverage | `./gradlew build` |
| Tests only | `./gradlew test` |
| Run with the rest of the platform | `make up` in `eudistack-platform-dev` |

Feature: `EUD-262`.
