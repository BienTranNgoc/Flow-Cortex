import { defineConfig } from 'orval';

// Contract-first pipeline (design.md D6, task 3.2): generates a typed client
// from the committed OpenAPI snapshot (docs/openapi.yaml, task 3.1) into
// src/api/generated/. `client: 'fetch'` needs no extra runtime dependency
// (native fetch); no `baseUrl` override so requests stay relative
// (`/api/v1/...`) — matching the same-origin `/api/` reverse-proxy nginx
// already uses in docker-compose (task 3.4) and the fixed `/api/v1` prefix
// D3 bakes into every route. `clean: true` removes stale generated files
// when the snapshot's operations change shape across regenerations.
export default defineConfig({
  flowCortex: {
    input: {
      target: '../docs/openapi.yaml',
    },
    output: {
      mode: 'single',
      target: 'src/api/generated/flowCortexApi.ts',
      client: 'fetch',
      clean: true,
      formatter: 'prettier',
    },
  },
});
