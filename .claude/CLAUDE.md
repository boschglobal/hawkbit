# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is hawkBit

Eclipse hawkBit is a domain-independent backend for rolling out software updates to constrained edge devices and IoT gateways. It exposes two device-facing APIs (DDI HTTP polling, DMF AMQP), an admin-facing Management REST API, and a Model Context Protocol (MCP) server for AI assistants. Multi-tenant, Spring Boot 4.0.5, Java 21. UI is Vaadin 25 Flow (server-side Java; no hand-written React/TypeScript).

## Build & Test Commands

```bash
# Full build (compile + test)
mvn clean install

# Compile only, skip tests
mvn clean install -DskipTests

# Run a single test class
mvn test -pl hawkbit-repository/hawkbit-repository-jpa -Dtest=ConcurrentDistributionSetInvalidationTest

# Run a single test method
mvn test -pl hawkbit-repository/hawkbit-repository-jpa -Dtest="ConcurrentDistributionSetInvalidationTest#testMethod"

# Build a single module and its dependencies
mvn install -pl hawkbit-mgmt/hawkbit-mgmt-resource -am

# License header check
mvn -PcheckLicense license:check
```

## Running the Server

```bash
# Start monolith with embedded H2 (dev/test)
java -jar hawkbit-monolith/hawkbit-update-server/target/hawkbit-update-server-0-SNAPSHOT.jar
# Default credentials: admin:admin — Swagger at http://localhost:8080/swagger-ui/index.html

# Start UI separately (port 8088)
java -jar hawkbit-ui/target/hawkbit-ui.jar

# Docker compose options in docker/ (postgres/ and mysql/ subdirs)
```

## Architecture

### Multi-Module Maven Layout

Code for a single feature typically spans 3–5 modules. Each domain folder (`hawkbit-ddi`, `hawkbit-dmf`, `hawkbit-mgmt`, `hawkbit-mcp`) follows the pattern `*-api` / `*-resource` (or `-amqp`, `-server` for transports) / `*-server` (standalone boot assembly) / `*-starter` (auto-config bundle).

- **`hawkbit-repository/hawkbit-repository-api`** — Domain service interfaces (`XxxManagement`). All business contracts live here.
- **`hawkbit-repository/hawkbit-repository-core`** — Shared domain primitives and base types consumed by both API and JPA layers.
- **`hawkbit-repository/hawkbit-repository-jpa`** — JPA implementations (`JpaXxxManagement`), Spring Data repositories (`XxxRepository`), entity classes (`JpaXxx`).
- **`hawkbit-repository/hawkbit-repository-jpa-api`** — JPA-facing contracts; **`-jpa-init`** — schema init/boot; **`-jpa-flyway`** — migrations (see below).
- **`hawkbit-repository/hawkbit-repository-jpa-hibernate`** / **`-jpa-eclipselink`** — ORM provider picks (default Hibernate, static-weaving EclipseLink alternative).
- **`hawkbit-repository/hawkbit-repository-test`** — `AbstractIntegrationTest` + `TestConfiguration` shared test harness.
- **`hawkbit-mgmt/hawkbit-mgmt-api`** — Management REST interfaces (`MgmtXxxRestApi`), request/response DTOs.
- **`hawkbit-mgmt/hawkbit-mgmt-resource`** — Management REST controllers (`MgmtXxxResource`) + `AbstractManagementApiIntegrationTest`.
- **`hawkbit-mgmt/hawkbit-mgmt-server`** / **`hawkbit-mgmt-starter`** — Standalone Mgmt server + starter.
- **`hawkbit-ddi/hawkbit-ddi-api`** / **`-resource`** / **`-security`** / **`-server`** / **`-starter`** — Direct Device Integration HTTP polling API.
- **`hawkbit-dmf/hawkbit-dmf-api`** / **`-amqp`** / **`-rabbitmq-test`** / **`-server`** / **`-starter`** — Device Management Federation AMQP.
- **`hawkbit-mcp/hawkbit-mcp-server`** / **`-starter`** — MCP server for AI assistants (NOT a device API).
- **`hawkbit-rest/hawkbit-rest-api`** / **`hawkbit-rest/hawkbit-rest-core`** — Shared REST scaffolding + `AbstractRestIntegrationTest` (MockMvc base).
- **`hawkbit-artifact/hawkbit-artifact-api`** — `ArtifactStorage` contract; **`hawkbit-artifact-fs`** — filesystem impl.
- **`hawkbit-ql-jpa`** — RSQL → JPA Specification translator.
- **`hawkbit-core`** — Permissions (`SpPermission`), multi-tenancy (`o.e.h.tenancy`), cross-cutting utilities.
- **`hawkbit-security-core`** — Authentication/authorization primitives.
- **`hawkbit-autoconfigure`** — Spring Boot auto-configuration wiring.
- **`hawkbit-ui`** — Vaadin 25 Flow admin console (pure Java views; separate boot jar, default port 8088; talks to mgmt API via Feign through `HawkbitMgmtClient`).
- **`hawkbit-sdk/{commons,demo,device,dmf,mgmt}`** — Client SDK + demo simulator (device/DMF/Mgmt consumers).
- **`hawkbit-monolith/hawkbit-update-server`** — Monolith assembly (main class `o.e.h.app.Start`); **`hawkbit-monolith/hawkbit-starter`** — bundled auto-config.
- **`hawkbit-test-report`** — Jacoco coverage aggregator; not a runtime artifact.

### Request Flow (Management API)

```
HTTP → MgmtXxxResource [hawkbit-mgmt-resource]
  → XxxManagement interface [hawkbit-repository-api]
    → JpaXxxManagement [hawkbit-repository-jpa]
      → XxxRepository (Spring Data) → JpaXxx entity
  → MgmtXxx response DTO [hawkbit-mgmt-api]
```

### Naming Conventions

| Layer | Pattern | Example |
|-------|---------|---------|
| Service interface | `XxxManagement` | `TargetManagement` |
| Service impl | `JpaXxxManagement` | `JpaTargetManagement` |
| Entity | `JpaXxx` | `JpaTarget` |
| Spring Data repo | `XxxRepository` | `TargetRepository` |
| Mgmt REST interface | `MgmtXxxRestApi` | `MgmtTargetRestApi` |
| Mgmt REST controller | `MgmtXxxResource` | `MgmtTargetResource` |
| Mgmt DTO (response) | `MgmtXxx` | `MgmtTarget` |
| Mgmt DTO (request) | `MgmtXxxRequestBody` | `MgmtTargetRequestBody` |
| DDI controller | `DdiXxxController` | `DdiRootController` |
| DMF handler | `AmqpXxxService` | `AmqpMessageHandlerService` |

### Flyway Migrations

Parallel SQL variants for each database in `hawkbit-repository/hawkbit-repository-jpa-flyway/src/main/resources/db/migration/`:
- `H2/` — dev/test
- `MYSQL/` — MySQL/MariaDB
- `POSTGRESQL/`

Naming: `V{major}_{minor}_{patch}__{description}__{DB}.sql` (e.g., `V1_20_1__spring_boot_4__H2.sql`). Baseline files use `B` prefix.

Schema changes require a migration file for **all three** databases.

### Test Infrastructure

Test base hierarchy (extends chain):

```
AbstractIntegrationTest          [hawkbit-repository-test]   — TestConfiguration, tenant setup
 └─ AbstractJpaIntegrationTest   [hawkbit-repository-jpa]    — JPA repo tests
 └─ AbstractRestIntegrationTest  [hawkbit-rest-core]         — MockMvc base
     └─ AbstractManagementApiIntegrationTest  [hawkbit-mgmt-resource]
     └─ AbstractDDiApiIntegrationTest         [hawkbit-ddi-resource]
 └─ AbstractAmqpIntegrationTest  [hawkbit-dmf-rabbitmq-test] — AMQP handler tests
```

- Pick the most-specific base; don't re-implement setup.
- Surefire excludes `**/Abstract*.java` from direct execution.

### Cross-Cutting Concerns

- **RSQL filtering**: `hawkbit-ql-jpa` parses RSQL queries and converts to JPA `Specification`s. New list endpoints must support RSQL via the existing infrastructure — don't reinvent.
- **Artifact storage**: code against the `ArtifactStorage` interface in `hawkbit-artifact-api`; never bind directly to `hawkbit-artifact-fs`.
- **Audit logging**: annotate every mutating Mgmt controller method with `@AuditLog`. Read-only endpoints don't need it.
- **Multi-tenancy**: every persistence query must be tenant-scoped. Don't bypass the tenant aware repositories in `hawkbit-core` (`o.e.h.tenancy`).
- **Permissions**: `@PreAuthorize` with `SpPermission` constants sits on `XxxManagement` interface methods (service layer), NOT on controller methods. A test (`RepositoryManagementMethodPreAuthorizeAnnotatedTest`) enforces that every Management method is annotated.
- **ORM neutrality**: write JPA that works on both Hibernate and EclipseLink — no provider-specific hints in entity/query code.

## Code Style

- Formatter rules: Eclipse formatter (`eclipse_codeformatter.xml`).
- Java: spaces-only indentation (4 spaces). XML: 3 spaces.
- Lombok is used project-wide. Prefer `@RequiredArgsConstructor` for DI, `@Slf4j` for logging.
- Utility library preference: JDK → Spring utilities → Apache Commons Lang. Do not pull new deps without discussion.
- No wildcards imports. No `System.out` — use SLF4J.

## Commit Conventions

- **Every commit requires `Signed-off-by`** — use `git commit -s` per Eclipse Contributor Agreement (ECA). PRs without DCO sign-off fail CI.
- **Every new file requires EPL-2.0 header** — see `licenses/LICENSE_HEADER_TEMPLATE.txt`. `mvn -PcheckLicense license:check` gates this.
- PRs should contain a single logical change; split unrelated refactors.

## Supported Databases

| Database | Status |
|----------|--------|
| H2 | Dev/Test only (default embedded) |
| MySQL / MariaDB | Production grade |
| PostgreSQL | Verified (see commit f2edc36e1 — `Add verify with Postgre`) |

## Golden Rules (Don'ts)

- **Don't skip Flyway variants.** Any schema change = three SQL files (H2, MYSQL, POSTGRESQL). Use the `hawkbit-flyway-migration` skill.
- **Don't add endpoints without permissions + audit.** Mgmt mutation = `@PreAuthorize` + `@AuditLog`. Use the `hawkbit-management-endpoint` skill.
- **Don't call JPA layer from controllers.** Controllers → `XxxManagement` interface → `JpaXxxManagement`.
- **Don't leak entities across modules.** `JpaXxx` stays inside `hawkbit-repository-jpa`; controllers return `MgmtXxx` DTOs.
- **Don't bypass tenancy, RSQL parsing, or paging helpers** — reuse existing infrastructure.
- **Don't use `--no-verify`, `--force-push`, or amend published commits** without explicit ask.
