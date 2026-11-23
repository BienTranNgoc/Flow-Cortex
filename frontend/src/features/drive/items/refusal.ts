import { problemDetailOf } from '../../../lib/problemMessage';
import { fieldErrorsFrom } from '../../auth/useAuth';
import { splitFieldErrors } from '../model/fieldErrors';

export interface Refusal {
  /** Sentences keyed by a field the form shows, as the server wrote them. */
  byField: Record<string, string>;
  /** Sentences for the form as a whole: refusals keyed by a hidden field, or a failure with no field map. */
  messages: string[];
}

/**
 * What a failed save has to show: the 400 `errors` map split by the fields the form has, or, with
 * no map (a 503, a 404, a network failure), the server's `detail` or `fallback`.
 */
export function refusalOf(error: unknown, visibleFields: readonly string[], fallback: string): Refusal {
  const refused = fieldErrorsFrom(error);
  if (Object.keys(refused).length === 0) {
    return { byField: {}, messages: [problemDetailOf(error) ?? fallback] };
  }
  const { byField, rest } = splitFieldErrors(refused, visibleFields);
  return { byField, messages: rest };
}
