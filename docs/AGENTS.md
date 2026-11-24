# Documentation rules

## Feature specifications and tests

- Use the [documentation index](README.md) to find the feature. Keep its specification in `features/<slug>/README.md` and add new specifications to the index. Document user flows and admin actions for the same business capability together.
- Write explanatory text and diagram labels in Vietnamese. Preserve technical identifiers. Verify described behavior against code, configuration, or tests, and mark proposed designs as planned.
- Keep representative test cases in the feature README or `testing.md`. Link automated cases to the test method that verifies the behavior, and mark cases awaiting automation according to the [testing guide](TESTING.md).

## Workflow diagrams

- The first PR that makes a new business workflow usable must include a **Use Case** diagram for actors and the actions they can take, and an **Activity** diagram for steps, branches, and wait points.
- Add or update a **Sequence** diagram when interactions between components need explanation. Add or update a **Class** diagram when domain relationships need explanation. For existing workflows, update the diagrams affected by the behavior change in the same PR.
- Follow the [Product Video example](features/product-video/README.md). Store PlantUML sources in `diagrams/source/` and matching SVGs in `diagrams/images/` under the feature directory. When a source changes, regenerate its same-named SVG, review the rendered diagram against the implementation, and verify the feature README links to both files.
