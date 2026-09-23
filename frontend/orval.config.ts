import { defineConfig } from 'orval';

// Typed client generated from the committed OpenAPI snapshot into src/api/generated/.
// `client: 'fetch'` needs no runtime dependency; no `baseUrl`, so requests stay relative
// (`/api/v1/...`) and go through the same-origin nginx `/api/` proxy. `clean: true`
// drops stale generated files when operations change shape.
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
