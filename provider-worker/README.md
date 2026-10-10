# Flow-Cortex provider worker

This directory owns provider-facing capabilities and secrets. The current skeleton contains:

- runtime-validated provider job and result contracts generated from `../contracts/provider-*.schema.json`;
- one ordered capability/profile registry;
- a provider adapter port;
- a deterministic fixture adapter for local tests.

The RabbitMQ consumer and OpenAI adapter are intentionally not implemented yet. Until those are wired, this package is a tested capability core rather than a deployable worker.
