---
name: hawkbit-vaadin-ui-view
description: Build admin views in hawkbit-ui using Vaadin 25. Use when adding a screen, grid, form, dialog, or detail panel to the bundled admin dashboard. Covers the Vaadin component conventions used in the codebase, Management API client wiring via HawkbitMgmtClient, TableView/SelectionGrid/Filter patterns for paged+filterable data, dialog patterns (Utils.BaseDialog), detail panels, and how the UI runs standalone (port 8088) against a separate mgmt API backend. Also use when the user mentions "UI view", "admin screen", "Vaadin view", "add a grid", "add a page to the dashboard", "hawkbit-ui", or is working on any file under hawkbit-ui/src/main/java/org/eclipse/hawkbit/ui/view/. If you're about to create or modify a view, filter, dialog, or grid component in hawkbit-ui, use this skill first.
---

# hawkbit-ui View Development Guide

The hawkbit-ui module is a standalone Spring Boot app (port 8088) that talks to a separate hawkBit Management API server via Feign-generated HTTP clients. It uses Vaadin 25 (Flow) for server-side UI rendering. Views are pure Java — no templates, no HTML.

## Module Layout

```
hawkbit-ui/src/main/java/org/eclipse/hawkbit/ui/
├── HawkbitUiApp.java          # @SpringBootApplication, AppShellConfigurator
├── HawkbitMgmtClient.java     # Feign client holder — all API access goes through here
├── MainLayout.java            # AppLayout shell: drawer nav + header
├── VaadinServiceInit.java     # VaadinServiceInitListener (timezone caching)
├── security/                  # Auth config (SecurityConfiguration, OAuth2)
├── component/                 # Reusable cross-view components
│   └── TargetActionsHistory.java
└── view/
    ├── Constants.java          # Shared column/label string constants
    ├── TargetView.java         # Targets screen (most complex view — reference impl)
    ├── DistributionSetView.java
    ├── SoftwareModuleView.java
    ├── RolloutView.java
    ├── TargetFilterQueryView.java
    ├── ConfigView.java         # Non-table view (simple form)
    ├── LoginView.java
    ├── AboutView.java
    └── util/
        ├── TableView.java      # Base class for all list/grid views
        ├── SelectionGrid.java  # Multi-select grid with RSQL filtering + lazy data
        ├── Filter.java         # Filter panel (simple + raw RSQL toggle)
        ├── Utils.java          # Shared helpers (dialogs, buttons, renderers, date formatting)
        └── LinkedTextArea.java # Card-based linked text display
```

## Architecture: How the UI Talks to the Backend

The UI does NOT access JPA or service layers directly. It uses `HawkbitMgmtClient`, which holds Feign-proxied interfaces for every Management REST API:

```java
@Getter
public class HawkbitMgmtClient {
    private final MgmtTargetRestApi targetRestApi;
    private final MgmtDistributionSetRestApi distributionSetRestApi;
    private final MgmtRolloutRestApi rolloutRestApi;
    private final MgmtSoftwareModuleRestApi softwareModuleRestApi;
    private final MgmtTargetFilterQueryRestApi targetFilterQueryRestApi;
    private final MgmtTargetTagRestApi targetTagRestApi;
    private final MgmtTargetTypeRestApi targetTypeRestApi;
    private final MgmtDistributionSetTypeRestApi distributionSetTypeRestApi;
    private final MgmtDistributionSetTagRestApi distributionSetTagRestApi;
    private final MgmtSoftwareModuleTypeRestApi softwareModuleTypeRestApi;
    private final MgmtTenantManagementRestApi tenantManagementRestApi;
    private final MgmtActionRestApi actionRestApi;
    // ...
}
```

`HawkbitMgmtClient` is a Spring bean, injected into view constructors. Every data fetch, create, update, or delete call goes through one of these Feign REST API interfaces. Response models are `Mgmt*` DTOs from `hawkbit-mgmt-api`.

## Creating a New Table/Grid View

Most views follow the same pattern: extend `TableView<T, ID>`, which provides a `SplitLayout` with a `SelectionGrid` (left) and optional detail panel (right), plus a `Filter` bar and add/remove controls.

### Step 1: Annotate the View Class

```java
@PageTitle("My Entities")
@Route(value = "my_entities", layout = MainLayout.class)
@RolesAllowed({ "MY_PERMISSION_READ" })
@Uses(Icon.class)
public final class MyEntityView extends TableView<MgmtMyEntity, Long> {
```

- `@PageTitle` — shown in header and browser tab.
- `@Route(value = "...", layout = MainLayout.class)` — path segment; `MainLayout` provides the nav drawer.
- `@RolesAllowed` — Spring Security role check. Use the `SpPermission` constant names.
- `@Uses(Icon.class)` — needed if any Vaadin icon is used in the view (Vaadin tree-shaking).

### Step 2: Constructor — Wire Up TableView

The `TableView` constructor takes these parameters in order:

1. **`Filter.Rsql rsql`** — primary filter (simple UI fields → RSQL string)
2. **`Filter.Rsql alternativeRsql`** — optional secondary filter (raw RSQL text field), pass `null` if not needed
3. **`SelectionGrid.EntityRepresentation<T, ID>`** — defines columns and ID extraction
4. **`BiFunction<Query<T, Void>, String, Stream<T>>`** — data fetch lambda (query + RSQL filter → stream of items)
5. **`Function<SelectionGrid<T, ID>, CompletionStage<Void>>`** — add handler (opens create dialog), or `null` to hide add button
6. **`Function<SelectionGrid<T, ID>, CompletionStage<Void>>`** — remove handler (deletes selected items), or `null`
7. **`Function<T, Component>`** — detail panel handler (returns the detail component for a selected row), or `null` for no detail panel

A 5-arg overload (no `alternativeRsql`, no `detailsButtonHandler`) exists for simpler views — delegates to the 7-arg form with `null` for both.

Example (following the established pattern):

```java
public MyEntityView(final HawkbitMgmtClient hawkbitClient) {
    super(
            new MyFilter(hawkbitClient),           // primary filter
            new MyRawFilter(),                     // optional raw RSQL filter (or null)
            new SelectionGrid.EntityRepresentation<>(MgmtMyEntity.class, MgmtMyEntity::getId) {
                @Override
                protected void addColumns(final Grid<MgmtMyEntity> grid) {
                    grid.addColumn(MgmtMyEntity::getName).setHeader(Constants.NAME)
                        .setAutoWidth(true).setKey("name").setSortable(true);
                    grid.addColumn(Utils.localDateTimeRenderer(MgmtMyEntity::getLastModifiedAt))
                        .setHeader(Constants.LAST_MODIFIED_AT).setAutoWidth(true)
                        .setKey("lastModifiedAt").setSortable(true);
                    // ... more columns
                }
            },
            (query, rsqlFilter) -> hawkbitClient.getMyEntityRestApi()
                    .getEntities(rsqlFilter, query.getOffset(), query.getPageSize(),
                            Utils.getSortParam(query.getSortOrders(), "name:asc"))
                    .getBody().getContent().stream(),
            selectionGrid -> new CreateMyEntityDialog(hawkbitClient).result(),
            selectionGrid -> {
                selectionGrid.getSelectedItems().forEach(
                        item -> hawkbitClient.getMyEntityRestApi().deleteEntity(item.getId()));
                return CompletableFuture.completedFuture(null);
            },
            item -> {
                final MyEntityDetailedView detailedView = new MyEntityDetailedView(hawkbitClient);
                detailedView.setItem(item);
                return detailedView;
            }
    );
}
```

### Step 3: Register in MainLayout Navigation

Add to `MainLayout.createNavigation()`:

```java
if (accessChecker.hasAccess(MyEntityView.class)) {
    nav.addItem(new SideNavItem("My Entities", MyEntityView.class, VaadinIcon.FILE.create()));
}
```

And add the import + add to `DEFAULT_VIEW_PRIORITY` list if it should be a default landing page candidate.

## Column Conventions

- Use `.setAutoWidth(true)` on every column.
- Use `.setKey("fieldName")` for sortable columns — the key maps to the sort parameter sent to the API.
- Use `.setSortable(true)` for columns that support server-side sorting.
- Use `Utils.localDateTimeRenderer(Entity::getTimestampField)` for timestamp columns.
- Use `new ComponentRenderer<>(CellComponent::new)` for custom cell rendering.
- Use `.setFlexGrow(0)` for fixed-width columns (status icons, actions).
- Use `.setFrozenToEnd(true)` for action columns that should stay visible during horizontal scroll.
- String constants for headers come from `Constants` interface — add new ones there if needed.

## Filter Patterns

### Simple Filter (Filter.Rsql)

Translates UI fields into RSQL:

```java
private static class MyFilter implements Filter.Rsql {
    private final TextField nameFilter = Utils.textField("Filter");

    @Override
    public List<Component> components() {
        return List.of(nameFilter);
    }

    @Override
    public String filter() {
        return Filter.filter(Map.of(
                "name", nameFilter.getOptionalValue().map(s -> "*" + s + "*")
        ));
    }
}
```

`Filter.filter()` accepts a `Map<Object, Object>` where:
- Key = field name string, or `List<String>` for OR across fields (e.g., `List.of("name", "controllerId")`)
- Value = `Optional<String>`, `List<String>`, or raw string. Empty/null values are excluded automatically.

For `CheckboxGroup` filters:

```java
CheckboxGroup<MgmtSomeType> type = new CheckboxGroup<>("Type");
// in filter():
"type", type.getSelectedItems().stream().map(MgmtSomeType::getKey).toList()
```

### Raw RSQL Filter (Filter.Rsql + Filter.RsqlRw)

Allows direct RSQL input and supports URL query param persistence:

```java
private static class MyRawFilter implements Filter.Rsql, Filter.RsqlRw {
    private final TextField textFilter = new TextField("Raw Filter", "<rsql filter>");

    @Override
    public List<Component> components() { return List.of(textFilter); }

    @Override
    public String filter() { return textFilter.getOptionalValue().orElse(null); }

    @Override
    public void setFilter(String filter) { textFilter.setValue(filter); }
}
```

Implement `Filter.RsqlRw` to enable filter state persistence via URL query params (`?q=...`). `TableView` handles the `BeforeEnterObserver` plumbing.

## Dialog Patterns

All dialogs extend `Utils.BaseDialog<T>`:

```java
private static class CreateMyEntityDialog extends Utils.BaseDialog<Void> {

    private final TextField name;
    private final Button create;

    private CreateMyEntityDialog(final HawkbitMgmtClient hawkbitClient) {
        super("Create My Entity");    // header title

        // Form fields (declared as fields so readyToCreate can reference them)
        name = Utils.textField(Constants.NAME, this::readyToCreate);
        name.focus();

        // Footer buttons
        create = Utils.tooltip(new Button("Create"), "Create (Enter)");
        create.setEnabled(false);
        create.addClickShortcut(Key.ENTER);
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        create.addClickListener(e -> {
            // call API
            hawkbitClient.getMyEntityRestApi().create(...);
            close();
        });

        final Button cancel = Utils.tooltip(new Button(CANCEL), CANCEL_ESC);
        cancel.addClickListener(e -> close());
        cancel.addClickShortcut(Key.ESCAPE);

        getFooter().add(cancel);
        getFooter().add(create);

        // Layout
        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setSpacing(false);
        layout.add(name, /* ... more fields ... */);
        add(layout);
        open();
    }

    private void readyToCreate(final HasValue.ValueChangeEvent<?> v) {
        create.setEnabled(!name.isEmpty() /* && other required fields */);
    }
}
```

Key conventions:
- `BaseDialog` provides close button in header, `CompletableFuture<T> result()`, draggable/resizable, strict modality.
- Enable/disable submit button via a `readyToCreate` validation method wired to field change listeners.
- `Key.ENTER` shortcut on submit, `Key.ESCAPE` on cancel.
- Cancel button always before submit in footer.
- Use `Utils.textField(label, changeListener)` for required text fields (auto-wires change mode).
- Use `Utils.nameComboBox(label, changeListener, fetchCallback)` for lazy-loading combo boxes.
- Use `Utils.actionTypeControls(default, forceTimePicker)` for action type selectors.
- Call `close()` inside click listener after successful API call.

## Detail Panel / Details View

Two patterns exist:

### 1. SplitLayout Detail Panel (side panel)

Pass a `detailsButtonHandler` lambda as the 7th arg to `TableView`. Each row gets an eye icon button; clicking opens the detail component in a split panel. See `TargetView` and `TargetFilterQueryView` for examples.

```java
item -> {
    final MyDetailedView view = new MyDetailedView(hawkbitClient);
    view.setItem(item);
    return view;
}
```

The detail view is typically a `VerticalLayout` containing a title `Span` and a `TabSheet` with tabs for different aspects.

### 2. Inline Row Details (expandable row)

Use `grid.setItemDetailsRenderer(new ComponentRenderer<>(() -> details, Details::setItem))` inside `EntityRepresentation.addColumns()`. See `DistributionSetView` and `RolloutView` for examples.

The detail component is typically a `FormLayout` with read-only fields:

```java
private static class MyDetails extends FormLayout {

    private final TextArea description = new TextArea(Constants.DESCRIPTION);
    private final TextField createdBy = Utils.textField(Constants.CREATED_BY);
    // ... more fields

    private MyDetails(final HawkbitMgmtClient hawkbitClient) {
        description.setMinLength(2);
        Stream.of(description, createdBy, /* ... */)
                .forEach(field -> {
                    field.setReadOnly(true);
                    add(field);
                });
        setResponsiveSteps(new ResponsiveStep("0", 2));
        setColspan(description, 2);
    }

    private void setItem(final MgmtMyEntity entity) {
        description.setValue(Objects.requireNonNullElse(entity.getDescription(), ""));
        createdBy.setValue(entity.getCreatedBy());
        // ...
    }
}
```

## Enriched Grid Items

When a grid row needs data from multiple API calls (e.g., target + its installed distribution set), create a wrapper class that extends the base DTO:

```java
@EqualsAndHashCode(callSuper = true)
public static class MyEnrichedItem extends MgmtMyEntity {
    Optional<MgmtRelatedEntity> related;
    static ObjectMapper objectMapper = new ObjectMapper();

    public static MyEnrichedItem from(HawkbitMgmtClient client, MgmtMyEntity base) {
        MyEnrichedItem item = objectMapper.convertValue(base, MyEnrichedItem.class);
        item.related = Optional.ofNullable(
                client.getRelatedApi().getRelated(item.getId()).getBody());
        return item;
    }
}
```

Use `objectMapper.convertValue()` to copy fields from the base DTO. Apply this in the data fetch lambda: `.map(m -> MyEnrichedItem.from(hawkbitClient, m))`.

## Action Columns

For rows with contextual action buttons (start/stop/delete), create a `HorizontalLayout` subclass:

```java
private static class Actions extends HorizontalLayout {
    private Actions(MgmtEntity entity, Grid<MgmtEntity> grid, HawkbitMgmtClient client) {
        // Conditionally add buttons based on entity state
        if ("READY".equalsIgnoreCase(entity.getStatus())) {
            add(Utils.tooltip(new Button(VaadinIcon.START_COG.create(), e -> {
                client.getApi().start(entity.getId());
                grid.getDataProvider().refreshAll();
            }), "Start"));
        }
        // Destructive actions get a ConfirmDialog
        add(Utils.tooltip(new Button(VaadinIcon.TRASH.create(), e -> {
            Utils.confirmDialog("Confirm deletion", "Are you sure?", "Delete", () -> {
                client.getApi().delete(entity.getId());
                grid.getDataProvider().refreshAll();
            }).open();
        }), "Delete"));
    }
}
```

## Non-Table Views

Simple views (like `ConfigView`) extend `VerticalLayout` directly and don't use `TableView`. Same annotations apply — `@Route`, `@RolesAllowed`, `@PageTitle`.

## Utility Methods Reference

| Method | Purpose |
|--------|---------|
| `Utils.textField(label)` | Read-only-ready text field |
| `Utils.textField(label, changeListener)` | Required text field with timeout change mode |
| `Utils.numberField(label, changeListener)` | Required number field |
| `Utils.nameComboBox(label, listener, fetchCallback)` | Lazy-loading ComboBox with name filter |
| `Utils.tooltip(component, text)` | Add tooltip to any component |
| `Utils.iconColored(icon, tooltip, color)` | Colored icon with tooltip |
| `Utils.deleteButton(tooltip, action)` | Trash icon + confirm dialog |
| `Utils.addRemoveControls(add, remove, grid, noPadding)` | Standard add/remove button bar |
| `Utils.confirmDialog(header, text, confirmText, action)` | Reusable confirm dialog |
| `Utils.errorNotification(throwable)` | Error notification toast |
| `Utils.localDateTimeRenderer(timestampFn)` | Timestamp column renderer (client timezone) |
| `Utils.localDateTimeFromTs(timestamp)` | Format timestamp as local datetime string |
| `Utils.getSortParam(sortOrders, defaultSort)` | Convert Vaadin sort orders to API sort string |
| `Utils.actionTypeControls(default, forceTime)` | Action type Select (Soft/Forced/TimedForced/DownloadOnly) |
| `Utils.BaseDialog<T>` | Base modal dialog with close, drag, resize |

## Build & Run

```bash
# Build UI module
mvn install -pl hawkbit-ui -DskipTests

# Run standalone (needs mgmt API running at default http://localhost:8080)
java -jar hawkbit-ui/target/hawkbit-ui.jar

# Dev mode with hot reload
cd hawkbit-ui && mvn spring-boot:run
```

Port 8088 by default. Connects to mgmt API at `hawkbit.url` (default `http://localhost:8080`).

## Checklist for a New View

1. Create view class in `o.e.h.ui.view` package, extending `TableView<T, ID>` or `VerticalLayout`
2. Add `@PageTitle`, `@Route(layout = MainLayout.class)`, `@RolesAllowed`, `@Uses(Icon.class)`
3. Add EPL-2.0 license header (see `licenses/LICENSE_HEADER_TEMPLATE.txt`)
4. Implement `Filter.Rsql` for the search bar
5. Implement `SelectionGrid.EntityRepresentation` to define columns
6. Wire data fetch lambda using `HawkbitMgmtClient` (Feign API)
7. Create dialog(s) extending `Utils.BaseDialog` for add/edit operations
8. Optionally create detail panel (side panel or inline row details)
9. Register in `MainLayout.createNavigation()` with access check
10. Add string constants to `Constants` interface if needed
11. Verify the required `MgmtXxxRestApi` interface is available in `HawkbitMgmtClient` — if not, add it
