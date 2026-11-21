# Media and payment contracts

These JSON Schemas (draft 2020-12) are the single source of truth for the messages exchanged between the API and the media worker: `media-job` (API → worker), `media-progress` and `media-result` (worker → API). They also define the payment shapes: `payment-provider-event` (the provider-agnostic event every provider interaction is normalised into) and `payment-journey-event` (the two bodies a payment page may post).

- **Java (API):** the records in `backend/modules/media/src/main/java/com/flowcortex/media/api/` are hand-written and validated at runtime with Bean Validation. `MediaContractSchemaTest` serializes fixtures and validates them against these files: `cd backend && ./gradlew :modules:media:test`.
- **Java (payments):** `backend/modules/billing` holds the payment schemas. `PaymentProviderEventSchemaTest` checks the provider-event record against its schema and `PaymentJourneySchemaAgreementTest` checks that the journey parser accepts exactly what its schema accepts: `cd backend && ./gradlew :modules:billing:test --tests com.flowcortex.billing.payment.PaymentProviderEventSchemaTest --tests com.flowcortex.billing.journey.PaymentJourneySchemaAgreementTest`. No Python model is generated for the payment schemas.
- **Python (worker):** the pydantic models in `worker/src/flowcortex_worker/contracts/` are generated. Regenerate with `cd worker && bash scripts/gen_contracts.sh` and commit the result.
- **CI:** `.github/workflows/contracts-ci.yml` runs the Java contract tests and fails when the regenerated Python models differ from the committed ones.

Change a schema, then update the Java records and regenerate the Python models in the same commit.
