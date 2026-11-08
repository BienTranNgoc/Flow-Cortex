export type RouteFocusTarget = 'page' | 'heading' | 'main';

interface RouteFocusInput {
  readonly state: unknown;
  readonly hasHeading: boolean;
}

function asksForHeadingFocus(state: unknown): boolean {
  return (
    typeof state === 'object' && state !== null && 'focusHeading' in state && !!state.focusHeading
  );
}

/**
 * Who moves focus after a page change, in order: a page that asked for it
 * (`state.focusHeading`, a run just started) keeps it; otherwise the page's
 * level-1 heading; otherwise the canvas.
 */
export function routeFocusTarget({ state, hasHeading }: RouteFocusInput): RouteFocusTarget {
  if (asksForHeadingFocus(state)) return 'page';
  return hasHeading ? 'heading' : 'main';
}
