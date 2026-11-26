/** Keys whose lowering is destructive downstream, so the console asks before sending it. */
export const CONFIRM_LOWERING_KEYS: ReadonlySet<string> = new Set([
  'media.editor.project_max_versions',
]);

export interface Lowering {
  readonly from: number;
  readonly to: number;
}

/**
 * The two values to name in the confirmation when saving `next` (or resetting to the configured value)
 * would lower `current` for a listed key; null when the change goes out at once.
 */
export function loweringToConfirm(key: string, current: unknown, next: unknown): Lowering | null {
  if (!CONFIRM_LOWERING_KEYS.has(key)) return null;
  if (typeof current !== 'number' || typeof next !== 'number') return null;
  return next < current ? { from: current, to: next } : null;
}
