# Media message contracts

These JSON Schemas (draft 2020-12) are the single source of truth for the messages exchanged between the API and the media worker: `media-job` (API → worker), `media-progress` and `media-result` (worker → API).

- **Java (API):** the records in `backend/modules/media/src/main/java/com/flowcortex/media/api/` are hand-written and validated at runtime with Bean Validation. `MediaContractSchemaTest` serializes fixtures and validates them against these files: `cd backend && ./gradlew :modules:media:test`.
- **Python (worker):** the pydantic models in `worker/src/flowcortex_worker/contracts/` are generated. Regenerate with `cd worker && bash scripts/gen_contracts.sh` and commit the result.
- **CI:** `.github/workflows/contracts-ci.yml` runs the Java contract test and fails when the regenerated Python models differ from the committed ones.

Change a schema, then update the Java records and regenerate the Python models in the same commit.

## Workflow artifact

`workflow-artifact.schema.json` describes the portable file an ADMIN exports from one environment and imports into another (format version 1: the workflow name and its ordered steps, nothing environment-bound). It has no worker counterpart, so no Python models are generated from it. The writer and the strict reader are hand-written in `backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/WorkflowArtifacts.java`; `WorkflowArtifactSchemaTest` validates what the writer emits and what the reader must refuse against the schema: `cd backend && ./gradlew :modules:workflow:test --tests '*WorkflowArtifactSchemaTest'`. Rules a schema cannot state (a step's order equals its position, the serialized config size, duplicate keys) are enforced by the reader and listed in the schema's description.
