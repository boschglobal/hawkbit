---
name: hawkbit-jpa-entity-design
description: Design JPA entities for Eclipse hawkBit that work under both EclipseLink and Hibernate, respect multi-tenancy (TenantAwareBaseEntity), and integrate with the repository-layer event bus. Use when introducing a new persistent domain object — adding a JPA @Entity class, defining its domain interface, wiring events for create/update/delete, creating its Spring Data repository, or modifying an existing entity's relationships or fields. Also use when the user mentions "new entity", "JPA entity", "domain object", "add table", "@Entity", "entity design", "entity hierarchy", or is working on any JpaXxx class in hawkbit-repository-jpa. If you're about to create or modify a file matching JpaXxx.java in hawkbit-repository-jpa/src/main/java/**/model/, use this skill first.
---

# hawkBit JPA Entity Design

This skill guides you through creating a new JPA entity in Eclipse hawkBit's multi-ORM, multi-tenant architecture. The codebase runs on both Hibernate and EclipseLink — entity code must be ORM-neutral. Tenancy, auditing, optimistic locking, and event publication are handled by the base class hierarchy.

## Entity Base Class Hierarchy

Pick the right base class for your entity. Each level adds fields and behavior:

```
AbstractJpaBaseEntity                      [hawkbit-repository-jpa-hibernate / -jpa-eclipselink]
  │  id (Long, @GeneratedValue IDENTITY)
  │  optLockRevision (int, @Version)
  │  createdBy, createdAt, lastModifiedBy, lastModifiedAt (Spring Data auditing)
  │  @PostPersist / @PostUpdate / @PostRemove → event bus
  │
  └─ AbstractJpaTenantAwareBaseEntity      [hawkbit-repository-jpa-hibernate / -jpa-eclipselink]
       │  tenant (String, 40 chars, immutable after insert)
       │  @PrePersist sets tenant from AccessContext
       │  equals/hashCode includes tenant
       │
       └─ AbstractJpaNamedEntity           [hawkbit-repository-jpa]
            │  name (String, 128 chars, required — NamedEntity.NAME_MAX_SIZE)
            │  description (String, 512 chars, optional — NamedEntity.DESCRIPTION_MAX_SIZE)
            │
            └─ AbstractJpaNamedVersionedEntity  [hawkbit-repository-jpa]
                 │  version (String, 64 chars, required)
```

**Decision guide:**

| Your entity has… | Extend |
|---|---|
| Only an ID (join table entity, pure FK holder) | `AbstractJpaTenantAwareBaseEntity` |
| A name + optional description | `AbstractJpaNamedEntity` |
| A name + version (software artifacts, packages) | `AbstractJpaNamedVersionedEntity` |

Every tenant-visible entity MUST extend `AbstractJpaTenantAwareBaseEntity` or one of its descendants. Non-tenant entities (rare, e.g. global config) extend `AbstractJpaBaseEntity` directly.

## File Placement

A new entity touches multiple modules. Here's where each piece goes:

| Artifact | Module | Path pattern |
|---|---|---|
| Domain interface (`Xxx`) | `hawkbit-repository-api` | `o.e.h.repository.model.Xxx` |
| JPA entity (`JpaXxx`) | `hawkbit-repository-jpa` | `o.e.h.repository.jpa.model.JpaXxx` |
| Spring Data repo (`XxxRepository`) | `hawkbit-repository-jpa` | `o.e.h.repository.jpa.repository.XxxRepository` |
| Event classes | `hawkbit-repository-api` | `o.e.h.repository.event.remote.entity.XxxCreatedEvent` etc. |
| Delete event (no entity ref) | `hawkbit-repository-api` | `o.e.h.repository.event.remote.XxxDeletedEvent` |
| Flyway migration | `hawkbit-repository-jpa-flyway` | `db/migration/{H2,MYSQL,POSTGRESQL}/` |

Do NOT put entity classes in the ORM-specific modules (`-jpa-hibernate`, `-jpa-eclipselink`). Those modules only contain the shared base classes.

## Step 1: Define the Domain Interface

Create an interface in `hawkbit-repository-api` that exposes only read accessors. This is what the rest of the codebase sees — the JPA entity is hidden behind it.

```java
package org.eclipse.hawkbit.repository.model;

public interface DeviceProfile extends NamedEntity {
    // NamedEntity already provides getName(), getDescription()
    // TenantAwareBaseEntity already provides getTenant()
    // BaseEntity already provides getId(), getCreatedBy(), getCreatedAt(), etc.

    String getFirmwareVersion();
    boolean isActive();
}
```

Interface hierarchy mirrors entity hierarchy:
- `BaseEntity` → `TenantAwareBaseEntity` → `NamedEntity` → `NamedVersionedEntity`

## Step 2: Create the JPA Entity

Place in `hawkbit-repository-jpa`:

```java
package org.eclipse.hawkbit.repository.jpa.model;

@NoArgsConstructor // required by JPA
@Getter
@Setter
@Entity
@Table(name = "sp_device_profile")
@SuppressWarnings("squid:S2160") // equals/hashCode handled by base
public class JpaDeviceProfile extends AbstractJpaNamedEntity
        implements DeviceProfile, EventAwareEntity {

    @Column(name = "firmware_version", length = 128)
    @Size(max = 128)
    private String firmwareVersion;

    @Column(name = "is_active")
    private boolean active;

    // relationships — always FetchType.LAZY
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_type_id")
    private JpaTargetType targetType;

    // constructor for programmatic creation
    public JpaDeviceProfile(final String name, final String description) {
        super(name, description);
    }

    // --- EventAwareEntity implementation ---
    @Override
    public void fireCreateEvent() {
        EventPublisherHolder.getInstance().getEventPublisher()
                .publishEvent(new DeviceProfileCreatedEvent(this));
    }

    @Override
    public void fireUpdateEvent() {
        EventPublisherHolder.getInstance().getEventPublisher()
                .publishEvent(new DeviceProfileUpdatedEvent(this));
    }

    @Override
    public void fireDeleteEvent() {
        EventPublisherHolder.getInstance().getEventPublisher()
                .publishEvent(new DeviceProfileDeletedEvent(getTenant(), getId(), getClass()));
    }
}
```

### Annotations You Get for Free

From the base hierarchy, your entity automatically gets — do NOT re-declare these:

- `@Id` + `@GeneratedValue(IDENTITY)` on `id`
- `@Version` on `optLockRevision`
- `@CreatedBy`, `@CreatedDate`, `@LastModifiedBy`, `@LastModifiedDate` with Spring Data auditing
- `@EntityListeners({ AuditingEntityListener.class, EntityInterceptorListener.class })`
- `@PrePersist` tenant injection from `AccessContext`
- `@PostPersist`, `@PostUpdate`, `@PostRemove` → event bus dispatch via `AfterTransactionCommitExecutor`
- `equals`/`hashCode` based on `(id, optLockRevision, tenant, class)`

### Table Naming

- Tables: `sp_<snake_case_entity>` (e.g. `sp_device_profile`)
- Join tables: `sp_<entity1>_<entity2>` (e.g. `sp_ds_sm`)
- Columns: `snake_case` (e.g. `firmware_version`, `target_type_id`)

## Step 3: Create Event Classes

Events use a split pattern: **created/updated** events carry an entity reference (can lazy-reload), **deleted** events carry only IDs (entity is gone).

### Created Event

```java
// In: hawkbit-repository-api, package o.e.h.repository.event.remote.entity

@NoArgsConstructor // for Jackson
public class DeviceProfileCreatedEvent extends RemoteEntityEvent<DeviceProfile>
        implements EntityCreatedEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonIgnore
    public DeviceProfileCreatedEvent(final DeviceProfile entity) {
        super(entity);
    }
}
```

### Updated Event

Same pattern, implement `EntityUpdatedEvent` instead of `EntityCreatedEvent`.

### Deleted Event

Deleted events extend `RemoteIdEvent` directly (no entity reference) and live in `o.e.h.repository.event.remote` (NOT the `entity` subpackage):

```java
// In: hawkbit-repository-api, package o.e.h.repository.event.remote

@NoArgsConstructor
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DeviceProfileDeletedEvent extends RemoteIdEvent implements EntityDeletedEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    public DeviceProfileDeletedEvent(
            final String tenant, final Long entityId,
            final Class<? extends TenantAwareBaseEntity> entityClass) {
        super(tenant, entityId, entityClass);
    }
}
```

If your deleted event needs extra context (like `TargetDeletedEvent` carries `controllerId`), add fields with `@Getter`.

### Event Hierarchy Reference

```
ApplicationEvent (Spring)
  └─ AbstractRemoteEvent          — UUID, @JsonTypeInfo for polymorphic serialization
       └─ RemoteTenantAwareEvent  — tenant string
            └─ RemoteIdEvent      — entityId + entityClass name
                 └─ RemoteEntityEvent<E> — transient entity ref, lazy reload
```

## Step 4: Create the Repository

```java
package org.eclipse.hawkbit.repository.jpa.repository;

@Transactional(readOnly = true)
public interface DeviceProfileRepository extends BaseEntityRepository<JpaDeviceProfile> {

    // custom finders use Specifications, not JPQL
    default Optional<JpaDeviceProfile> findByName(final String name) {
        return findOne(DeviceProfileSpecifications.hasName(name));
    }

    default JpaDeviceProfile getByName(final String name) {
        return findByName(name)
                .orElseThrow(() -> new EntityNotFoundException(DeviceProfile.class, name));
    }

    // tenant-scoped bulk delete
    @Modifying
    @Transactional
    @Query("DELETE FROM JpaDeviceProfile dp WHERE dp.tenant = :tenant")
    void deleteByTenant(@Param("tenant") String tenant);
}
```

`BaseEntityRepository<T>` provides: `PagingAndSortingRepository`, `CrudRepository`, `JpaSpecificationExecutor`, `JpaSpecificationEntityGraphExecutor`, `NoCountSliceRepository`, `ACMRepository`.

## ORM Compatibility Rules

The codebase must run on both Hibernate and EclipseLink. These are the pitfalls:

### 1. Lazy Loading on @OneToOne

Hibernate does not support lazy loading on `@OneToOne` — it always eager-fetches the target side. The project works around this by modeling logical one-to-one as `@OneToMany`:

```java
// DO THIS — lazy works on both ORMs
@OneToMany(fetch = FetchType.LAZY, mappedBy = "target",
           cascade = { CascadeType.ALL }, orphanRemoval = true)
@PrimaryKeyJoinColumn
private List<JpaAutoConfirmationStatus> autoConfirmationStatus;
```

For `@ManyToOne`, lazy loading works fine on both ORMs — use it always.

### 2. Property vs. Field Access for Audit Fields

The base classes use `@Access(AccessType.PROPERTY)` on audit getters so setter validation logic runs. Don't override audit setters.

**Hibernate version** puts `@Column` annotations on the getters (property access).
**EclipseLink version** puts `@Column` annotations on the fields (field access).

Your concrete entity doesn't need to worry about this — the base class handles it. Just don't annotate your own fields with `@Access(AccessType.PROPERTY)` unless you have setter logic.

### 3. No Provider-Specific Annotations in Entity Code

Never use these in `hawkbit-repository-jpa` entities:
- `org.hibernate.*` annotations (`@Where`, `@Filter`, `@SQLDelete`, `@DynamicUpdate`)
- `org.eclipse.persistence.*` annotations
- Hibernate-specific `@Type` or `@TypeDef`

Use standard JPA `@Convert` with `AttributeConverter` instead. The project has `MapAttributeConverter<JAVA_TYPE, DB_TYPE>` for enum-to-DB mappings.

### 4. Named Entity Graphs for Fetch Optimization

Use `@NamedEntityGraph` on entities — this is JPA-standard and works on both ORMs:

```java
@NamedEntityGraphs({
    @NamedEntityGraph(
        name = "DeviceProfile.details",
        attributeNodes = {
            @NamedAttributeNode("targetType")
        })
})
```

Repository methods can reference these graphs via `JpaSpecificationEntityGraphExecutor`.

### 5. JPQL Compatibility

Write JPQL that both ORMs accept:
- No Hibernate-specific functions (use JPA Criteria API or Specifications instead)
- No EclipseLink `FUNC()` calls
- Prefer Specification-based queries over raw `@Query` JPQL

### 6. Enum Persistence

Use `@Convert` with `MapAttributeConverter` subclasses — NOT `@Enumerated`:

```java
@Column(name = "status", nullable = false)
@Convert(converter = DeviceStatusConverter.class)
private DeviceStatus status;
```

The converter maps enum values to stable integer codes, preventing ordinal/name drift.

## Relationship Patterns

### Fetch Strategy

ALL relationships must be `FetchType.LAZY`. No exceptions. Use entity graphs for eager loading at query time.

### Cascade Types

| Relationship | Cascade | When |
|---|---|---|
| Parent owns child lifecycle | `CascadeType.ALL`, `orphanRemoval = true` | Child cannot exist without parent |
| Parent creates children | `CascadeType.PERSIST` | Children may outlive parent |
| Parent deletes children | `CascadeType.REMOVE` | Children die with parent |
| Reference only (FK) | No cascade | Most `@ManyToOne` relationships |

### ManyToMany with Join Table

```java
@ManyToMany(targetEntity = JpaSoftwareModule.class, fetch = FetchType.LAZY)
@JoinTable(
    name = "sp_ds_sm",
    joinColumns = { @JoinColumn(name = "ds_id", nullable = false) },
    inverseJoinColumns = { @JoinColumn(name = "sm_id", nullable = false) })
private Set<JpaSoftwareModule> modules = new HashSet<>();
```

### ElementCollection for Key-Value Maps

```java
@ElementCollection
@CollectionTable(
    name = "sp_device_profile_attributes",
    joinColumns = { @JoinColumn(name = "device_profile_id", nullable = false) })
@MapKeyColumn(name = "attribute_key", length = 128)
@Column(name = "attribute_value", length = 512)
private Map<String, String> attributes = new HashMap<>();
```

## Metadata Support

If your entity needs user-defined key-value metadata, implement `WithMetadata<MV, MVI>`:

```java
public class JpaDeviceProfile extends AbstractJpaNamedEntity
        implements DeviceProfile, WithMetadata<String, String>, EventAwareEntity {

    @ElementCollection
    @CollectionTable(
        name = "sp_device_profile_metadata",
        joinColumns = { @JoinColumn(name = "dp_id", nullable = false) })
    @MapKeyColumn(name = "meta_key", length = DeviceProfile.METADATA_MAX_KEY_SIZE)
    @Column(name = "meta_value", length = DeviceProfile.METADATA_MAX_VALUE_SIZE)
    private Map<String, String> metadata = new HashMap<>();

    @Override
    public Map<String, String> getMetadata() { return metadata; }
}
```

Then your Management class extends `AbstractJpaRepositoryWithMetadataManagement` instead of `AbstractJpaRepositoryManagement`.

## Event Publication Internals

Understanding how events fire helps you avoid subtle bugs:

1. Entity is persisted/updated/deleted by JPA
2. ORM fires `@PostPersist` / `@PostUpdate` / `@PostRemove` on `AbstractJpaBaseEntity`
3. Base class checks `this instanceof EventAwareEntity` — if yes, calls `fireXxxEvent()`
4. But NOT directly — it wraps in `AfterTransactionCommitExecutor.afterCommit(runnable)`
5. Event actually publishes only after the transaction commits successfully
6. If the transaction rolls back, no event fires

**Special case — `JpaTarget`**: overrides `postUpdate()` to NO-OP because `EntityPropertyChangeListener` (registered separately in both Hibernate and EclipseLink configs) handles Target updates with field-level filtering. This avoids publishing update events for high-frequency polling fields (`lastTargetQuery`, `address`).

If your entity needs similar field-filtering on updates, you'll need to:
1. Override `postUpdate()` in your entity to no-op
2. Add filtering logic to `EntityPropertyChangeListener` in BOTH ORM modules

## Flyway Migration

Every new entity needs a migration file for all three databases. Use the `hawkbit-flyway-migration` skill for detailed guidance. Quick reference:

```sql
-- V1_21_1__add_device_profile__H2.sql
CREATE TABLE sp_device_profile (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    optlock_revision INT DEFAULT 0,
    tenant VARCHAR(40) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    last_modified_by VARCHAR(64) NOT NULL,
    last_modified_at BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    firmware_version VARCHAR(128),
    is_active BOOLEAN DEFAULT FALSE,
    target_type_id BIGINT,
    PRIMARY KEY (id)
);
```

Three files required: `H2/`, `MYSQL/`, `POSTGRESQL/` — type mappings differ.

## Checklist: New Entity

1. [ ] Domain interface in `hawkbit-repository-api` (`o.e.h.repository.model`)
2. [ ] JPA entity in `hawkbit-repository-jpa` (`o.e.h.repository.jpa.model`)
   - Extends correct base class
   - Implements domain interface + `EventAwareEntity`
   - `@Table(name = "sp_xxx")`
   - `@NoArgsConstructor` + `@SuppressWarnings("squid:S2160")`
   - All relationships `FetchType.LAZY`
   - No ORM-specific annotations
3. [ ] Event classes in `hawkbit-repository-api`
   - `XxxCreatedEvent` extends `RemoteEntityEvent<Xxx>` implements `EntityCreatedEvent`
   - `XxxUpdatedEvent` extends `RemoteEntityEvent<Xxx>` implements `EntityUpdatedEvent`
   - `XxxDeletedEvent` extends `RemoteIdEvent` implements `EntityDeletedEvent`
4. [ ] Repository in `hawkbit-repository-jpa` extends `BaseEntityRepository<JpaXxx>`
5. [ ] Flyway migrations for H2, MySQL, PostgreSQL (use `hawkbit-flyway-migration` skill)
6. [ ] Management interface in `hawkbit-repository-api` with `@PreAuthorize`
7. [ ] JPA Management impl in `hawkbit-repository-jpa`
8. [ ] Enum converters use `MapAttributeConverter`, not `@Enumerated`
