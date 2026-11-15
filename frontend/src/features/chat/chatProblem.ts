import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { formatDateTime } from '../../lib/format';
import i18n, { activeLocale, type Locale } from '../../lib/i18n';

const PROBLEM_TYPE_BASE = 'https://flow-cortex.dev/problems/';
/** The server's day limit rolls over at midnight here, so the resume time is shown in this zone. */
const RESUME_TIME_ZONE = 'Asia/Ho_Chi_Minh';

export type ChatProblemKind = 'not-found' | 'disabled' | 'quota' | 'in-progress' | 'alert';

export interface ChatProblem {
  kind: ChatProblemKind;
  message: string;
  /** For a quota refusal: when chatting is possible again, already phrased. */
  resume?: string;
}

/** The `code` of a problem response, else the last segment of a Flow-Cortex problem `type`. */
export function chatProblemCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || !error.problem) return undefined;
  const { code, type } = error.problem as { code?: unknown; type?: unknown };
  if (typeof code === 'string') return code;
  if (typeof type === 'string' && type.startsWith(PROBLEM_TYPE_BASE)) {
    const segment = type.slice(PROBLEM_TYPE_BASE.length);
    return segment && !segment.includes('/') ? segment : undefined;
  }
  return undefined;
}

function resumeLine(error: ApiError, t: TFunction, locale: Locale): string | undefined {
  const raw = (error.problem as { resumeAt?: unknown } | undefined)?.resumeAt;
  if (typeof raw !== 'string' || Number.isNaN(new Date(raw).getTime())) return undefined;
  const time = formatDateTime(raw, locale, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: RESUME_TIME_ZONE,
  });
  return t('chat.problems.resumeAt', { time });
}

/**
 * Maps a failed chat request to what the page shows. The sentences the product promises (gone,
 * switched off, still answering) come from the locale; other refusals show the server's own
 * localized detail.
 */
export function chatProblem(
  error: unknown,
  t: TFunction = i18n.t,
  locale: Locale = activeLocale(),
): ChatProblem {
  switch (chatProblemCode(error)) {
    case 'chat-conversation-not-found':
      return { kind: 'not-found', message: t('chat.problems.notFound') };
    case 'chat-disabled':
      return { kind: 'disabled', message: t('chat.problems.off') };
    case 'chat-turn-in-progress':
      return { kind: 'in-progress', message: t('chat.problems.inProgress') };
    case 'chat-quota': {
      const apiError = error as ApiError;
      const detail = apiError.problem?.detail;
      // The server's detail already names the resume time, in the request's language.
      if (detail) return { kind: 'quota', message: detail };
      const resume = resumeLine(apiError, t, locale);
      const message = t('chat.problems.quota');
      return resume ? { kind: 'quota', message, resume } : { kind: 'quota', message };
    }
    default:
      return {
        kind: 'alert',
        message:
          (error instanceof ApiError ? error.problem?.detail : undefined) ??
          t('chat.problems.generic'),
      };
  }
}
