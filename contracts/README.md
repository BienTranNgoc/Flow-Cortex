# Media message contracts

These JSON Schemas (draft 2020-12) are the single source of truth for the messages exchanged between the API and the media worker: `media-job` (API → worker), `media-progress` and `media-result` (worker → API).

- **Java (API):** the records in `backend/modules/media/src/main/java/com/flowcortex/media/api/` are hand-written and validated at runtime with Bean Validation. `MediaContractSchemaTest` serializes fixtures and validates them against these files: `cd backend && ./gradlew :modules:media:test`.
- **Python (worker):** the pydantic models in `worker/src/flowcortex_worker/contracts/` are generated. Regenerate with `cd worker && bash scripts/gen_contracts.sh` and commit the result.
- **CI:** `.github/workflows/contracts-ci.yml` runs the Java contract test and fails when the regenerated Python models differ from the committed ones.

Change a schema, then update the Java records and regenerate the Python models in the same commit.
