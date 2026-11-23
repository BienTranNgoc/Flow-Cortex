export interface SplitFieldErrors {
  byField: Record<string, string>;
  rest: string[];
}

/**
 * Separates server refusals keyed by a field the form shows from the others, whose sentences the
 * form shows as one message each so no refusal is dropped.
 */
export function splitFieldErrors(
  errors: Record<string, string>,
  visibleFields: readonly string[],
): SplitFieldErrors {
  const byField: Record<string, string> = {};
  const rest: string[] = [];
  for (const [field, sentence] of Object.entries(errors)) {
    if (visibleFields.includes(field)) {
      byField[field] = sentence;
    } else if (!rest.includes(sentence)) {
      rest.push(sentence);
    }
  }
  return { byField, rest };
}
