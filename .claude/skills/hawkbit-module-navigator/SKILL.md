---
name: hawkbit-module-navigator
description: Navigate Eclipse hawkbit's multi-module Maven layout to find where code lives and trace request flows. Use whenever working in the hawkbit codebase and you need to locate a feature across modules, identify which module owns a concern (REST API vs persistence vs UI vs device-facing), find where an interface is defined vs implemented, or trace a request from REST controller through service to JPA entity. Also use when the user asks "where is X", "which module has Y", "how does Z flow through the code", mentions a domain concept (target, rollout, distribution set, action, artifact, software module) and you need to find the right files, or when exploring unfamiliar parts of the codebase. If you're about to grep blindly across all modules, use this skill first — it tells you exactly where to look.
---

# Hawkbit Module Navigator

Eclipse hawkbit is a multi-module Maven project (~15 top-level modules, many with submodules). Code for a single feature typically spans 3–5 modules. This skill tells you which module owns what, so you search the right place on the first try.

## Module Ownership Map

Each module has a single clear responsibility. When looking for code, start here:

| Concern | Module | Submodule | Base Package |
|---------|--------|-----------|-------------|
| **Domain interfaces** (service contracts) | `hawkbit-repository` | `hawkbit-repository-api` | `o.e.h.repository` |
| **JPA entities** (persistence model) | `hawkbit-repository` | `hawkbit-repository-jpa` | `o.e.h.repository.jpa.model` |
| **Service implementations** | `hawkbit-repository` | `hawkbit-repository-jpa` | `o.e.h.repository.jpa.management` |
| **Spring Data repositories** | `hawkbit-repository` | `hawkbit-repository-jpa` | `o.e.h.repository.jpa.repository` |
| **ORM provider (Hibernate)** | `hawkbit-repository` | `hawkbit-repository-jpa-hibernate` | `o.e.h.repository.jpa` |
| **ORM provider (EclipseLink)** | `hawkbit-repository` | `hawkbit-repository-jpa-eclipselink` | `o.e.h.repository.jpa` |
| **Domain helpers** (non-JPA) | `hawkbit-repository` | `hawkbit-repository-core` | `o.e.h.repository`, `o.e.h.event` |
| **JPA SPI** (interceptors, tx hooks) | `hawkbit-repository` | `hawkbit-repository-jpa-api` | `o.e.h.repository.jpa` |
| **Flyway DB init** | `hawkbit-repository` | `hawkbit-repository-jpa-init` | `o.e.h.repository.jpa.init` |
| **Flyway migrations** | `hawkbit-repository` | `hawkbit-repository-jpa-flyway` | resources only |
| **Management REST controllers** | `hawkbit-mgmt` | `hawkbit-mgmt-resource` | `o.e.h.mgmt.rest.resource` |
| **Management REST API interfaces** | `hawkbit-mgmt` | `hawkbit-mgmt-api` | `o.e.h.mgmt.rest.api` |
| **Management REST DTOs** | `hawkbit-mgmt` | `hawkbit-mgmt-api` | `o.e.h.mgmt.json.model` |
| **DDI REST controllers** | `hawkbit-ddi` | `hawkbit-ddi-resource` | `o.e.h.ddi.rest.resource` |
| **DDI JSON models/contracts** | `hawkbit-ddi` | `hawkbit-ddi-api` | `o.e.h.ddi.json.model` |
| **DDI security** | `hawkbit-ddi` | `hawkbit-ddi-security` | `o.e.h.security.controller` |
| **DMF AMQP handlers** | `hawkbit-dmf` | `hawkbit-dmf-amqp` | `o.e.h.amqp` |
| **DMF message models** | `hawkbit-dmf` | `hawkbit-dmf-api` | `o.e.h.dmf.json.model` |
| **MCP server** | `hawkbit-mcp` | `hawkbit-mcp-server` | `o.e.h.mcp` |
| **RSQL query parsing** | `hawkbit-ql-jpa` | — | `o.e.h.ql.rsql` |
| **RSQL → JPA Specification** | `hawkbit-ql-jpa` | — | `o.e.h.ql.jpa` |
| **Artifact storage abstraction** | `hawkbit-artifact` | `hawkbit-artifact-api` | `o.e.h.artifact` |
| **Artifact filesystem impl** | `hawkbit-artifact` | `hawkbit-artifact-fs` | `o.e.h.artifact` |
| **Security, permissions, tenancy** | `hawkbit-core` | — | `o.e.h.auth`, `o.e.h.tenancy` |
| **REST API models** (OpenAPI, error DTOs) | `hawkbit-rest` | `hawkbit-rest-api` | `o.e.h.rest`, `o.e.h.rest.json.model` |
| **REST infrastructure** | `hawkbit-rest` | `hawkbit-rest-core` | `o.e.h.rest` |
| **Spring Boot auto-config** | `hawkbit-autoconfigure` | — | `o.e.h.autoconfigure` |
| **Vaadin admin UI** | `hawkbit-ui` | — | `o.e.h.ui` |
| **Client SDKs** | `hawkbit-sdk` | `hawkbit-sdk-mgmt`, `-device`, `-dmf` | `o.e.h.sdk` |
| **SDK shared utilities** | `hawkbit-sdk` | `hawkbit-sdk-commons` | `o.e.h.sdk` |
| **Device simulator** | `hawkbit-sdk` | `hawkbit-sdk-demo` | `o.e.h.sdk.demo` |
| **Monolith assembly** | `hawkbit-monolith` | `hawkbit-update-server` | `o.e.h.app` |
| **Repository test support** | `hawkbit-repository` | `hawkbit-repository-test` | test infrastructure |
| **DMF integration tests** | `hawkbit-dmf` | `hawkbit-dmf-rabbitmq-test` | test infrastructure |

> `o.e.h` = `org.eclipse.hawkbit`

> **Convention:** Most API-facing modules (`hawkbit-mgmt`, `-ddi`, `-dmf`, `-mcp`) also have `*-server` and `*-starter` submodules for Spring Boot assembly and auto-configuration. Similarly `hawkbit-monolith` has `hawkbit-starter`. These rarely contain domain logic — look there when debugging auto-config or standalone deployment.

## Request Flow Patterns

### Management API (admin HTTP REST)

A typical admin REST call flows through four layers across three modules:

```
HTTP request
  → MgmtXxxResource          [hawkbit-mgmt/hawkbit-mgmt-resource]
    → XxxManagement (iface)   [hawkbit-repository/hawkbit-repository-api]
      → JpaXxxManagement      [hawkbit-repository/hawkbit-repository-jpa]
        → XxxRepository       [hawkbit-repository/hawkbit-repository-jpa]
          → JpaXxx (entity)   [hawkbit-repository/hawkbit-repository-jpa]
  → MgmtXxx (response DTO)   [hawkbit-mgmt/hawkbit-mgmt-api]
```

Example for targets:
- Controller: `MgmtTargetResource` → interface `MgmtTargetRestApi`
- Service: `TargetManagement<T extends Target>` → impl `JpaTargetManagement`
- Repository: `TargetRepository extends HawkbitBaseRepository<JpaTarget, Long>`
- Entity: `JpaTarget`
- DTO: `MgmtTarget`, `MgmtTargetRequestBody`

### DDI (device polling HTTP REST)

Devices poll the server for pending actions and report feedback:

```
Device polls GET /{tenant}/controller/v1/{controllerId}
  → DdiRootController            [hawkbit-ddi/hawkbit-ddi-resource]
    → ControllerManagement        [hawkbit-repository/hawkbit-repository-api]
      → JpaControllerManagement   [hawkbit-repository/hawkbit-repository-jpa]
  → DdiControllerBase (JSON)      [hawkbit-ddi/hawkbit-ddi-api]
```

### DMF (AMQP message-based)

Devices connect via RabbitMQ instead of HTTP:

```
AMQP message arrives
  → AmqpMessageHandlerService    [hawkbit-dmf/hawkbit-dmf-amqp]
    → ControllerManagement       [hawkbit-repository/hawkbit-repository-api]
      → JpaControllerManagement  [hawkbit-repository/hawkbit-repository-jpa]
  → AmqpMessageDispatcherService [hawkbit-dmf/hawkbit-dmf-amqp]
```

## How to Use This Skill

### Finding where a concept lives

1. **Identify the layer** — is it an API endpoint, domain logic, persistence, or UI?
2. **Consult the ownership map** above to narrow to 1–2 modules
3. **Search within that module** using grep or find

### Tracing a feature end-to-end

For any domain concept (e.g., "rollout"), the pieces live in predictable locations:

| Layer | Where to Look | Naming Pattern |
|-------|--------------|----------------|
| REST controller | `hawkbit-mgmt/hawkbit-mgmt-resource` | `MgmtRolloutResource` |
| REST API contract | `hawkbit-mgmt/hawkbit-mgmt-api` | `MgmtRolloutRestApi` |
| Response DTO | `hawkbit-mgmt/hawkbit-mgmt-api` | `MgmtRollout` |
| Request DTO | `hawkbit-mgmt/hawkbit-mgmt-api` | `MgmtRolloutRequestBody` |
| Service interface | `hawkbit-repository/hawkbit-repository-api` | `RolloutManagement` or `RolloutHandler` |
| Service impl | `hawkbit-repository/hawkbit-repository-jpa` | `JpaRolloutManagement` |
| Spring Data repo | `hawkbit-repository/hawkbit-repository-jpa` | `RolloutRepository` |
| JPA entity | `hawkbit-repository/hawkbit-repository-jpa` | `JpaRollout` |
| DDI representation | `hawkbit-ddi/hawkbit-ddi-api` | `DdiXxx` (if device-facing) |
| DMF message model | `hawkbit-dmf/hawkbit-dmf-api` | `DmfXxx` (if AMQP-facing) |
| Flyway migration | `hawkbit-repository/hawkbit-repository-jpa-flyway` | `V<version>__<description>.sql` |
| UI view | `hawkbit-ui` | Vaadin component class |

### Domain concepts → classes

| Domain Concept | Service Interface | JPA Entity | Mgmt DTO |
|---------------|-------------------|------------|----------|
| Target (device) | `TargetManagement` | `JpaTarget` | `MgmtTarget` |
| Distribution Set | `DistributionSetManagement` | `JpaDistributionSet` | `MgmtDistributionSet` |
| Software Module | `SoftwareModuleManagement` | `JpaSoftwareModule` | `MgmtSoftwareModule` |
| Artifact | `ArtifactManagement` | `JpaArtifact` | `MgmtArtifact` |
| Rollout | `RolloutManagement` / `RolloutHandler` | `JpaRollout` | `MgmtRollout` |
| Rollout Group | (via RolloutManagement) | `JpaRolloutGroup` | `MgmtRolloutGroup` |
| Action | `DeploymentManagement` | `JpaAction` | `MgmtAction` |
| Action Status | (via DeploymentManagement) | `JpaActionStatus` | `MgmtActionStatus` |
| Target Filter | `TargetFilterQueryManagement` | `JpaTargetFilterQuery` | `MgmtTargetFilterQuery` |
| Target Tag | `TargetTagManagement` | `JpaTargetTag` | `MgmtTag` |
| DS Tag | `DistributionSetTagManagement` | `JpaDistributionSetTag` | `MgmtTag` |
| Confirmation | `ConfirmationManagement` | — | — |
| Tenant Config | `TenantConfigurationManagement` | `JpaTenantConfiguration` | `MgmtSystemTenantConfigurationValue` |

### Interface ↔ Implementation split

All domain service interfaces live in `hawkbit-repository-api`. All JPA implementations live in `hawkbit-repository-jpa/management/`. The naming convention is consistent:

- Interface: `XxxManagement` in package `org.eclipse.hawkbit.repository`
- Implementation: `JpaXxxManagement` in package `org.eclipse.hawkbit.repository.jpa.management`

This separation allows swapping persistence without touching API consumers.

### Cross-cutting concerns

| Concern | Where |
|---------|-------|
| Permissions / roles | `hawkbit-core` → `SpPermission` |
| Multi-tenancy | `hawkbit-core` → `o.e.h.tenancy` |
| RSQL filtering | `hawkbit-ql-jpa` → `RsqlParser` (`o.e.h.ql.rsql`), `SpecificationBuilder` (`o.e.h.ql.jpa`) |
| Artifact storage | `hawkbit-artifact` → `ArtifactStorage` interface |
| Auto-configuration | `hawkbit-autoconfigure` → `@AutoConfiguration` classes |
| REST utilities | `hawkbit-rest/hawkbit-rest-core` → `FileStreamingUtil`, `HttpUtil` |

## Quick Search Commands

When you know the layer but not the exact class:

```bash
# Find all Management REST controllers
find hawkbit-mgmt/hawkbit-mgmt-resource -name "Mgmt*Resource.java"

# Find all service interfaces
find hawkbit-repository/hawkbit-repository-api -name "*Management.java"

# Find all JPA entities
find hawkbit-repository/hawkbit-repository-jpa -path "*/model/Jpa*.java"

# Find all Spring Data repositories
find hawkbit-repository/hawkbit-repository-jpa -name "*Repository.java" -path "*/repository/*"

# Find DDI endpoints
find hawkbit-ddi/hawkbit-ddi-resource -name "Ddi*Controller.java"

# Find DMF handlers
find hawkbit-dmf/hawkbit-dmf-amqp -name "Amqp*Service.java"

# Find Flyway migrations for a specific DB (dirs are uppercase: H2, POSTGRESQL, MYSQL)
find hawkbit-repository/hawkbit-repository-jpa-flyway -path "*/H2/*" -name "V*.sql"
find hawkbit-repository/hawkbit-repository-jpa-flyway -path "*/POSTGRESQL/*" -name "V*.sql"
find hawkbit-repository/hawkbit-repository-jpa-flyway -path "*/MYSQL/*" -name "V*.sql"
```
