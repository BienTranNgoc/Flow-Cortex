/** The longest sample input the server stores; a longer handed-over value is not a sample. */
export const SAMPLE_INPUT_MAX = 10_000;

/** The input the onboarding sample card hands to the workflow page through router state. */
export function sampleInputFrom(state: unknown): string {
  if (typeof state !== 'object' || state === null || !('sampleInput' in state)) return '';
  const { sampleInput } = state;
  return typeof sampleInput === 'string' && sampleInput.length <= SAMPLE_INPUT_MAX
    ? sampleInput
    : '';
}
