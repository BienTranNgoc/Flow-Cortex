import type { TFunction } from 'i18next';
import { ApiError } from '@flow-cortex/api-client';

/**
 * A refused decision (403, 409, 422…) carries a sentence written for the member; a 5xx detail
 * does not, so the caller falls back to its own wording.
 */
export function refusalReason(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.status >= 500) return undefined;
  return error.problem?.detail;
}

/** The server's message for one named field of a refused request. */
export function fieldMessage(error: unknown, field: string): string | undefined {
  return error instanceof ApiError ? error.problem?.errors?.[field] : undefined;
}

/**
 * The refusals of a review decision the app words itself, in the app's language: an ended review
 * and answers sent before the questions were prepared.
 */
export function ownSentence(error: unknown, t: TFunction): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  if (error.problem?.code === 'REVIEW_EXPIRED') return t('workflow.approval.expired');
  if (error.problem?.code === 'REVIEW_QUESTIONS_NOT_READY') return t('workflow.qa.notReady');
  return undefined;
}

/** The sentence a refused review decision shows: the app's own wording, else the server's reason. */
export function decisionRefusal(error: unknown, t: TFunction): string | undefined {
  return ownSentence(error, t) ?? refusalReason(error);
}
