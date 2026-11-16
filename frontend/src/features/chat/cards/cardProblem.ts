import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import i18n, { activeLocale, type Locale } from '../../../lib/i18n';
import { startProblem } from '../../workflow/startProblem';
import { chatProblemCode } from '../chatProblem';

/** What a card shows for a refused confirm. */
export type CardProblem =
  | { kind: 'credits'; message: string; canRunAnyway: boolean }
  | { kind: 'expired' }
  | { kind: 'invalid'; message: string; fields: Record<string, string> }
  | { kind: 'alert'; message: string };

export function cardProblem(
  error: unknown,
  t: TFunction = i18n.t,
  locale: Locale = activeLocale(),
): CardProblem {
  const code = chatProblemCode(error);
  if (code === 'chat-card-images-expired') return { kind: 'expired' };
  if (code === 'chat-card-invalid' && error instanceof ApiError) {
    return {
      kind: 'invalid',
      message: error.problem?.detail ?? t('chat.card.invalid'),
      fields: { ...error.problem?.errors },
    };
  }
  const problem = startProblem(error, t('chat.card.failed'), t, locale);
  if (problem.kind === 'credits') {
    return { kind: 'credits', message: problem.message, canRunAnyway: problem.canRunAnyway };
  }
  return {
    kind: 'alert',
    message: 'message' in problem ? problem.message : problem.summary,
  };
}
