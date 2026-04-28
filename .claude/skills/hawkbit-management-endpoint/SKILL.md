---
name: hawkbit-management-endpoint
description: Add or modify Management API endpoints in Eclipse hawkBit. Use when exposing a new admin-facing REST capability or modifying an existing one. Covers the API interface / Resource implementation split, request/response DTOs with mapper classes, RSQL filter plumbing, pageable responses with offset/limit, sort parameter sanitization, OpenAPI/Swagger meta-annotations, permission documentation, HATEOAS link building, @AuditLog annotations on mutations, and integration tests using MockMvc. Also use when the user mentions "REST endpoint", "management API", "add endpoint", "CRUD endpoint", "controller", "resource class", "REST API", "pageable", "RSQL", or is working on any MgmtXxxRestApi / MgmtXxxResource class. If you're about to create or modify a file in hawkbit-mgmt, use this skill first.
---

# Hawkbit Management Endpoint

Management API endpoints follow a strict layered pattern across two modules. Every endpoint touches multiple files in a predictable way. This skill covers the full lifecycle of adding or modifying an endpoint.

## Module Layout

Management API code lives in sibling modules under `hawkbit-mgmt/`:

```
hawkbit-mgmt/
  hawkbit-mgmt-api/          <- API interfaces + DTOs (contract)
    src/main/java/org/eclipse/hawkbit/mgmt/
      rest/api/               <- MgmtXxxRestApi interfaces
      json/model/             <- MgmtXxx response DTOs, MgmtXxxRequestBody request DTOs
  hawkbit-mgmt-resource/      <- Resource implementations (business logic delegation)
    src/main/java/org/eclipse/hawkbit/mgmt/rest/resource/
      MgmtXxxResource.java    <- @RestController implementing the API interface
      mapper/                 <- MgmtXxxMapper classes (domain <-> DTO conversion)
      util/                   <- PagingUtility, SortUtility
    src/test/java/            <- Integration tests (MockMvc)
  hawkbit-mgmt-starter/       <- Auto-configuration for Management API
  hawkbit-mgmt-server/        <- Server configuration
```

Shared REST infrastructure (response annotations, `ResponseList`, exception handler) lives in `hawkbit-rest/hawkbit-rest-api` and `hawkbit-rest/hawkbit-rest-core`.

The service layer (domain logic) lives in `hawkbit-repository`:
- Interfaces: `hawkbit-repository/hawkbit-repository-api` -> `XxxManagement`
- Implementations: `hawkbit-repository/hawkbit-repository-jpa` -> `JpaXxxManagement`

## The API Interface / Resource Split

Every Management endpoint has two parts:

### 1. API Interface (contract)

File: `hawkbit-mgmt-api/.../rest/api/MgmtXxxRestApi.java`

Declares method signatures, Spring MVC mappings, and all OpenAPI annotations. Contains zero implementation logic.

**Important:** Do NOT add `@RequestMapping` at the interface level — omitted intentionally to avoid CVE-2021-22044 in Feign client.

```java
// no request mapping specified here to avoid CVE-2021-22044 in Feign client
@Tag(name = "Xxx", description = "REST API for Xxx operations.",
     extensions = @Extension(name = OpenApi.X_HAWKBIT,
         properties = @ExtensionProperty(name = "order", value = XXX_ORDER)))
public interface MgmtXxxRestApi {

    String XXX_V1 = MgmtRestConstants.REST_V1 + "/xxxs";

    @Operation(summary = "Return all Xxxs",
        description = "Handles the GET request of retrieving all xxxs. Required Permission: READ_XXX")
    @GetIfExistResponses
    @GetMapping(value = XXX_V1, produces = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE })
    ResponseEntity<PagedList<MgmtXxx>> getAll(
        @RequestParam(value = MgmtRestConstants.REQUEST_PARAMETER_SEARCH, required = false)
            @Schema(description = "Query fields based on the Feed Item Query Language (FIQL).")
            String rsqlParam,
        @RequestParam(value = MgmtRestConstants.REQUEST_PARAMETER_PAGING_OFFSET,
            defaultValue = MgmtRestConstants.REQUEST_PARAMETER_PAGING_DEFAULT_OFFSET)
            @Schema(description = "The paging offset (default is 0)")
            int pagingOffsetParam,
        @RequestParam(value = MgmtRestConstants.REQUEST_PARAMETER_PAGING_LIMIT,
            defaultValue = MgmtRestConstants.REQUEST_PARAMETER_PAGING_DEFAULT_LIMIT)
            @Schema(description = "The maximum number of entries in a page (default is 50)")
            int pagingLimitParam,
        @RequestParam(value = MgmtRestConstants.REQUEST_PARAMETER_SORTING, required = false)
            @Schema(description = "The query parameter sort ...")
            String sortParam);

    @Operation(summary = "Return Xxx by id",
        description = "Handles the GET request of retrieving a single xxx. Required Permission: READ_XXX")
    @GetResponses
    @GetMapping(value = XXX_V1 + "/{xxxId}", produces = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE })
    ResponseEntity<MgmtXxx> get(@PathVariable("xxxId") Long xxxId);

    @Operation(summary = "Create Xxx(s)",
        description = "Handles the POST request of creating new xxxs. Required Permission: CREATE_XXX")
    @PostCreateResponses
    @PostMapping(value = XXX_V1,
        consumes = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE },
        produces = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE })
    ResponseEntity<List<MgmtXxx>> create(@RequestBody List<MgmtXxxRequestBody> bodies);

    @Operation(summary = "Update Xxx",
        description = "Handles the PUT request of updating an xxx. Required Permission: UPDATE_XXX")
    @PutResponses
    @PutMapping(value = XXX_V1 + "/{xxxId}",
        consumes = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE },
        produces = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE })
    ResponseEntity<MgmtXxx> update(@PathVariable("xxxId") Long xxxId, @RequestBody MgmtXxxRequestBody body);

    @Operation(summary = "Delete Xxx",
        description = "Handles the DELETE request of deleting an xxx. Required Permission: DELETE_XXX")
    @DeleteResponses
    @DeleteMapping(value = XXX_V1 + "/{xxxId}")
    ResponseEntity<Void> delete(@PathVariable("xxxId") Long xxxId);
}
```

**ID type varies by entity.** Most entities use `Long xxxId` for path variables. Target is the exception — it uses `String targetId` (the controllerId). Check existing endpoints for the entity you're working with.

**`@Valid` on request bodies.** Some endpoints add `@Valid` on `@RequestBody` for bean validation (e.g., `@RequestBody @Valid MgmtDistributionSetAssignments`). Add it when the request DTO has validation constraints.
```

### 2. Resource Implementation

File: `hawkbit-mgmt-resource/.../rest/resource/MgmtXxxResource.java`

`@RestController` that implements the API interface. Delegates to the service layer and uses mappers.

```java
@Slf4j
@RestController
public class MgmtXxxResource implements MgmtXxxRestApi {

    private final XxxManagement<? extends Xxx> xxxManagement;
    private final MgmtXxxMapper mgmtXxxMapper;

    // constructor injection

    @Override
    public ResponseEntity<PagedList<MgmtXxx>> getAll(
            final String rsqlParam, final int pagingOffsetParam,
            final int pagingLimitParam, final String sortParam) {
        final Pageable pageable = PagingUtility.toPageable(
            pagingOffsetParam, pagingLimitParam, sanitizeXxxSortParam(sortParam));

        final Page<? extends Xxx> page;
        if (rsqlParam != null) {
            page = xxxManagement.findByRsql(rsqlParam, pageable);
        } else {
            page = xxxManagement.findAll(pageable);
        }

        return ResponseEntity.ok(new PagedList<>(
            MgmtXxxMapper.toResponse(page.getContent()), page.getTotalElements()));
    }

    @Override
    public ResponseEntity<MgmtXxx> get(final Long xxxId) {
        final Xxx entity = xxxManagement.get(xxxId);
        final MgmtXxx response = MgmtXxxMapper.toResponse(entity);
        MgmtXxxMapper.addXxxLinks(response);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<MgmtXxx>> create(final List<MgmtXxxRequestBody> bodies) {
        final Collection<? extends Xxx> created = xxxManagement.create(
            mgmtXxxMapper.fromRequest(bodies));
        return new ResponseEntity<>(MgmtXxxMapper.toResponse(created), HttpStatus.CREATED);
    }

    @Override
    @AuditLog(entity = "Xxx", type = AuditLog.Type.UPDATE, description = "Update Xxx")
    public ResponseEntity<MgmtXxx> update(final Long xxxId, final MgmtXxxRequestBody body) {
        final Xxx updated = xxxManagement.update(xxxId, /* build update from body */);
        return ResponseEntity.ok(MgmtXxxMapper.toResponse(updated));
    }

    @Override
    @AuditLog(entity = "Xxx", type = AuditLog.Type.DELETE, description = "Delete Xxx")
    public ResponseEntity<Void> delete(final Long xxxId) {
        xxxManagement.delete(xxxId);
        return ResponseEntity.noContent().build();
    }
}
```

## DTOs: Request and Response Bodies

### Response DTO

File: `hawkbit-mgmt-api/.../json/model/xxx/MgmtXxx.java`

Extends `MgmtNamedEntity` (which extends `MgmtBaseEntity` which extends `RepresentationModel`). This gives you `createdBy`, `createdAt`, `lastModifiedBy`, `lastModifiedAt`, `name`, `description` for free, plus HATEOAS link support.

```java
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MgmtXxx extends MgmtNamedEntity {

    @JsonProperty(required = true)
    @Schema(description = "...", example = "...")
    private Long xxxId;

    @Schema(description = "...", example = "...")
    private String someField;
}
```

**Base class hierarchy:**
- `MgmtBaseEntity` provides: `createdBy`, `createdAt`, `lastModifiedBy`, `lastModifiedAt` + `RepresentationModel` (HATEOAS links)
- `MgmtNamedEntity` adds: `name`, `description`
- If entity has no name/description, extend `MgmtBaseEntity` directly

### Request DTO

File: `hawkbit-mgmt-api/.../json/model/xxx/MgmtXxxRequestBody.java`

```java
@Data
@Accessors(chain = true)
@ToString
public class MgmtXxxRequestBody {

    @JsonProperty(required = true)
    @Schema(description = "The name of the entity", example = "myXxx")
    private String name;

    @Schema(description = "The description", example = "Example description")
    private String description;

    // entity-specific fields...
}
```

## Mapper Class

File: `hawkbit-mgmt-resource/.../rest/resource/mapper/MgmtXxxMapper.java`

Handles bidirectional conversion between DTOs and domain objects. Often a `@Service` (if it needs injected dependencies) or a utility class with static methods.

```java
@Service
public class MgmtXxxMapper {

    // Domain -> Response DTO (list)
    public static List<MgmtXxx> toResponse(final Collection<? extends Xxx> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return new ResponseList<>(entities.stream()
            .map(MgmtXxxMapper::toResponse).toList());
    }

    // Domain -> Response DTO (single)
    public static MgmtXxx toResponse(final Xxx entity) {
        final MgmtXxx response = new MgmtXxx();
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        // map entity-specific fields...
        response.add(linkTo(methodOn(MgmtXxxRestApi.class).get(entity.getId()))
            .withSelfRel().expand());
        return response;
    }

    // Request DTO -> Domain Create object
    public List<Create> fromRequest(final Collection<MgmtXxxRequestBody> bodies) {
        return bodies.stream().map(this::fromRequest).toList();
    }

    // Add detail links (for single-entity GET responses)
    // Naming convention: add<Entity>Links()
    public static void addXxxLinks(final MgmtXxx response) {
        response.add(linkTo(methodOn(MgmtSomeRelatedRestApi.class)
            .getRelated(response.getId()))
            .withRel("related").expand());
    }
}
```

**Key HATEOAS patterns:**
- Self link: `linkTo(methodOn(Api.class).get(id)).withSelfRel().expand()`
- Related link: `linkTo(methodOn(Api.class).getRelated(id)).withRel("relName").expand()`
- Always call `.expand()` at the end to resolve URI template variables
- Wrap list results in `ResponseList<>` — see below

**`ResponseList<T>`** lives in `hawkbit-rest/hawkbit-rest-api/.../rest/json/model/ResponseList.java`. It implements `List<T>` AND extends `RepresentationModel`, ensuring HAL links are serialized properly on list responses. Always use it as the return value from `toResponse` list methods:

```java
return new ResponseList<>(entities.stream().map(MgmtXxxMapper::toResponse).toList());
```

**`SortDirection`** enum lives in `org.eclipse.hawkbit.mgmt.rest.api.SortDirection`. Used in mapper link-building when constructing default sort parameters for action history links (e.g., `ActionFields.ID.getName() + ":" + SortDirection.DESC`).

## RSQL Filter Plumbing

RSQL lets clients filter results with a `q` query parameter (FIQL syntax).

### In the API interface

```java
@RequestParam(value = MgmtRestConstants.REQUEST_PARAMETER_SEARCH, required = false)
@Schema(description = "Query fields based on the Feed Item Query Language (FIQL). See Entity Definitions for available fields.")
String rsqlParam,
```

### In the Resource implementation

The `rsqlParam` string flows directly to the service layer:

```java
if (rsqlParam != null) {
    page = xxxManagement.findByRsql(rsqlParam, pageable);
} else {
    page = xxxManagement.findAll(pageable);
}
```

The service interface must declare `findByRsql(String rsql, Pageable pageable)`. The JPA implementation uses `hawkbit-ql-jpa` to parse the RSQL string into a JPA `Specification`.

### XxxFields enum

Each filterable entity needs a `XxxFields` enum implementing `QueryField`. This enum maps REST-facing field names to JPA entity paths and is used by both RSQL parsing and sort validation.

Look in `hawkbit-repository/hawkbit-repository-api` for existing `*Fields` enums (e.g., `TargetFields`, `DistributionSetFields`, `ActionFields`).

## Pageable Responses

### PagedList wrapper

All paginated endpoints return `PagedList<MgmtXxx>`:

```java
return ResponseEntity.ok(new PagedList<>(rest, page.getTotalElements()));
```

`PagedList` serializes to:
```json
{
  "content": [...],
  "total": 125,
  "size": 50
}
```

### PagingUtility

Located at `hawkbit-mgmt-resource/.../rest/resource/util/PagingUtility.java`. Sanitizes offset/limit values:

```java
final Pageable pageable = PagingUtility.toPageable(
    pagingOffsetParam, pagingLimitParam, sanitizeXxxSortParam(sortParam));
```

- Default limit: 50
- Max limit: 500
- Default offset: 0

### Standard paging parameters

Every list endpoint declares these four parameters (copy from an existing endpoint):
1. `rsqlParam` (String, `q`)
2. `pagingOffsetParam` (int, `offset`, default `"0"`)
3. `pagingLimitParam` (int, `limit`, default `"50"`)
4. `sortParam` (String, `sort`)

## Sort Parameter Sanitization

### Adding a sanitize method

Add to `PagingUtility.java`:

```java
public static Sort sanitizeXxxSortParam(final String sortParam) {
    if (sortParam == null) {
        return Sort.by(Direction.ASC, XxxFields.ID.getName());
    }
    return Sort.by(SortUtility.parse(XxxFields.class, sortParam));
}
```

`SortUtility.parse()` validates field names against the enum and parses direction. Sort format from client: `field1:ASC,field2:DESC`.

The sort sanitizer goes in `PagingUtility` as a static method. The `XxxFields` enum provides allowed field names.

## OpenAPI / Swagger Annotations

### Custom response meta-annotations

Located in `hawkbit-rest/hawkbit-rest-api/.../rest/ApiResponsesConstants.java`. Use these instead of raw `@ApiResponses`:

| Annotation | HTTP Status (beyond common) | Use for |
|-----------|-------------|---------|
| `@GetResponses` | 200, 404 | GET single entity |
| `@GetIfExistResponses` | 200 (no 404) | GET paginated lists (returns empty list, not 404) |
| `@PostCreateResponses` | 201, 409, 415, 429 | POST create (returns body) |
| `@PostCreateNoContentResponses` | 204, 409, 415, 429 | POST create (no body) |
| `@PostUpdateResponses` | 200, 404, 409, 415 | POST for updates (returns body) |
| `@PostUpdateNoContentResponses` | 204, 404, 415 | POST for updates (no body) |
| `@PutResponses` | 200, 404, 409, 415, 429 | PUT update (returns body) |
| `@PutNoContentResponses` | 204, 404, 409, 415, 429 | PUT update (no body) |
| `@DeleteResponses` | 204, 404 | DELETE |

All include `@CommonErrorResponses` (400, 401, 403, 406) automatically.

**Key distinction:** Use `@GetIfExistResponses` for paginated list endpoints (they return empty content, never 404). Use `@GetResponses` for single-entity GETs (404 when entity not found).

### @Tag for controller grouping

Every API interface gets a `@Tag` with an order value from `MgmtRestConstants`:

```java
@Tag(name = "Xxxs", description = "REST API for Xxx operations.",
     extensions = @Extension(name = OpenApi.X_HAWKBIT,
         properties = @ExtensionProperty(name = "order", value = MgmtRestConstants.XXX_ORDER)))
```

If adding a new entity, add a new order constant to `MgmtRestConstants`. Existing orders use increments of 1000 (1000, 2000, 3000...).

### Content types

Always produce both HAL and plain JSON:
```java
produces = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE }
```

For POST/PUT, consume both:
```java
consumes = { HAL_JSON_VALUE, APPLICATION_JSON_VALUE }
```

Import these from `org.springframework.http.MediaType` (`APPLICATION_JSON_VALUE`) and `org.springframework.hateoas.MediaTypes` (`HAL_JSON_VALUE`).

## Permission Documentation

Permissions are enforced in the service layer (not the REST controller). Document them in `@Operation.description`:

```java
@Operation(summary = "Return all Xxxs",
    description = "Handles the GET request of retrieving all xxxs. Required Permission: READ_XXX")
```

Permission constants live in `hawkbit-core/.../auth/SpPermission.java`. The CRUD pattern is:
- `CREATE_XXX` for POST
- `READ_XXX` for GET
- `UPDATE_XXX` for PUT
- `DELETE_XXX` for DELETE

Some entities have special permissions (e.g., `APPROVE_ROLLOUT`, `HANDLE_ROLLOUT`, `READ_TARGET_SECURITY_TOKEN`).

## @AuditLog Annotation

Apply `@AuditLog` on Resource methods that mutate state (UPDATE, DELETE). Never on GET.

**Note:** In current codebase, CREATE methods (e.g., `createTargets`, `createDistributionSets`) typically do NOT have `@AuditLog`. UPDATE and DELETE methods consistently do. Follow existing patterns for the entity you're working with.

```java
@Override
@AuditLog(entity = "Target", type = AuditLog.Type.UPDATE, description = "Update Target")
public ResponseEntity<MgmtTarget> updateTarget(...) { ... }

@Override
@AuditLog(entity = "Target", type = AuditLog.Type.DELETE, description = "Delete Target")
public ResponseEntity<Void> deleteTarget(...) { ... }
```

`AuditLog` is in `hawkbit-core/.../audit/AuditLog.java`.

Properties:
- `entity` -- domain entity name (e.g., "Target", "DistributionSet")
- `type` -- one of `CREATE`, `READ`, `UPDATE`, `DELETE`, `EXECUTE`
- `description` -- human-readable action description
- `level` -- defaults to `INFO` (also `WARN`, `ERROR`)
- `logParams` -- defaults to `{"*"}` (log all params)
- `logResponse` -- defaults to `false`

## Integration Tests

File: `hawkbit-mgmt-resource/src/test/java/.../rest/resource/MgmtXxxResourceTest.java`

Tests use MockMvc with `AbstractManagementApiIntegrationTest` as the base class. Do NOT add `@SpringBootTest`, `@AutoConfigureMockMvc`, or `@ExtendWith` — they are inherited from `AbstractRestIntegrationTest` up the chain.

```java
class MgmtXxxResourceTest extends AbstractManagementApiIntegrationTest {

    @Test
    void getReturnsOk() throws Exception {
        // use testdataFactory (inherited) or management service directly
        final Xxx entity = testdataFactory.createXxx("testId");

        mvc.perform(get(MgmtXxxRestApi.XXX_V1 + "/" + entity.getId())
                .contentType(APPLICATION_JSON))
            .andDo(MockMvcResultPrinter.print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name", equalTo(entity.getName())));
    }

    @Test
    void getPagedWithRsql() throws Exception {
        mvc.perform(get(MgmtXxxRestApi.XXX_V1 + "?limit=10&sort=name:ASC&offset=0&q=name==a"))
            .andExpect(status().isOk())
            .andDo(MockMvcResultPrinter.print());
    }

    @Test
    void createReturnsCreated() throws Exception {
        final String body = new JSONObject()
            .put("name", "testXxx")
            .put("description", "test description")
            .toString();

        mvc.perform(post(MgmtXxxRestApi.XXX_V1)
                .content("[" + body + "]")
                .contentType(APPLICATION_JSON))
            .andDo(MockMvcResultPrinter.print())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$[0].name", equalTo("testXxx")));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        final Xxx entity = testdataFactory.createXxx("testId");

        mvc.perform(delete(MgmtXxxRestApi.XXX_V1 + "/" + entity.getId()))
            .andExpect(status().isNoContent());
    }

    @Test
    void getNonExistentReturnsNotFound() throws Exception {
        mvc.perform(get(MgmtXxxRestApi.XXX_V1 + "/999999")
                .contentType(APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }
}
```

**Inherited test infrastructure:**
- `mvc` (MockMvc) — from `AbstractRestIntegrationTest`
- `testdataFactory` — from `AbstractIntegrationTest`, provides convenience methods for creating test entities
- `@SpringBootTest` + `@AutoConfigureMockMvc` — from `AbstractRestIntegrationTest`
- `@ContextConfiguration` + `@TestPropertySource` — from `AbstractManagementApiIntegrationTest`

## Files to Create/Modify When Adding a New Endpoint

For a brand-new entity `Xxx`, create or modify these files in order:

### In `hawkbit-mgmt-api`:

1. **`MgmtXxxRestApi.java`** -- API interface with all endpoint methods
2. **`MgmtXxx.java`** -- Response DTO (extends `MgmtNamedEntity` or `MgmtBaseEntity`)
3. **`MgmtXxxRequestBody.java`** -- Request DTO
4. **`MgmtRestConstants.java`** -- Add `XXX_ORDER` constant (if new @Tag)

### In `hawkbit-mgmt-resource`:

5. **`MgmtXxxResource.java`** -- `@RestController` implementing the API interface
6. **`mapper/MgmtXxxMapper.java`** -- Domain <-> DTO conversion + HATEOAS links
7. **`util/PagingUtility.java`** -- Add `sanitizeXxxSortParam()` method
8. **`MgmtXxxResourceTest.java`** -- Integration tests

### In `hawkbit-repository-api` (if service doesn't exist yet):

9. **`XxxManagement.java`** -- Service interface with CRUD + findByRsql

### When adding a method to an existing endpoint:

Only touch the API interface (add method signature + annotations) and the Resource (add implementation + `@AuditLog` if UPDATE/DELETE). Update the mapper if new response fields are needed.

## Checklist

Before finalizing:

1. API interface has `@Tag` with order constant, no `@RequestMapping` at interface level
2. Every method has `@Operation(summary, description)` including required permission
3. Correct response annotation: `@GetIfExistResponses` for lists, `@GetResponses` for single, etc.
4. Paginated endpoints have all four parameters (q, offset, limit, sort)
5. `@Schema` descriptions on all DTO fields; class-level `@Schema` with JSON example on response DTOs
6. Response DTO extends correct base class (`MgmtNamedEntity` or `MgmtBaseEntity`)
7. Mapper adds self-link on every response object via `toResponse`
8. Mapper's `add<Entity>Links()` method adds relationship links for single-entity GET
9. UPDATE/DELETE methods have `@AuditLog` with correct entity/type/description (CREATE typically omitted)
10. Sort sanitizer added to `PagingUtility` using correct `XxxFields` enum
11. RSQL branch in resource: `if (rsqlParam != null) findByRsql else findAll`
12. Content types: produce `HAL_JSON_VALUE` and `APPLICATION_JSON_VALUE`
13. POST create returns `HttpStatus.CREATED` (201), DELETE returns `noContent()` (204)
14. List `toResponse` wraps result in `ResponseList<>` (from `hawkbit-rest-api`)
15. Integration test extends `AbstractManagementApiIntegrationTest` (no extra annotations)
16. Integration test covers: GET single, GET paged, POST create, PUT update, DELETE, 404 case
