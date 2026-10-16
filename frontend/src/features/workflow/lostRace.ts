import { ApiError } from '@flow-cortex/api-client';

/**
 * True when the server rejected a decision because someone else moved the task on — a stale tab,
 * or a second approval of the same revision. The response body names no editable field, so the
 * panel's only honest move is to tell the reviewer to reload.
 */
export function isLostRace(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}