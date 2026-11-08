import { ApiError } from '@flow-cortex/api-client';

/** The server's own `detail` when the failure is a problem response that carries one. */
export function problemDetailOf(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const detail = error.problem?.detail?.trim();
  return detail ? detail : undefined;
}
