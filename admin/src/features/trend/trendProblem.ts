import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { supportProblemCode } from '../support/unwrapSupport';

export type NameField = 'nameVi' | 'nameEn';

/** What a refused write says in the console's language; the server's own text is never shown. */
export interface TrendFailure {
  /** The machine reason of the problem, when the body names one. */
  code: string | undefined;
  /** A refusal that belongs to the page or the dialog as a whole. */
  message: string | undefined;
  /** A refusal that belongs to one name field. */
  fields: Partial<Record<NameField, string>>;
}

const GENERAL_CODES: ReadonlySet<string> = new Set([
  'category-not-found',
  'tag-not-found',
  'taxonomy-order-stale',
  'taxonomy-full',
]);

/** The machine reason of a refused request, or `undefined` for a failure that is not an API answer. */
export function trendProblemCode(error: unknown): string | undefined {
  return supportProblemCode(error);
}

/** Templates holding the record, from `category-in-use`; the server's "at least 1" floor when absent. */
export function templateCountOf(error: unknown): number {
  if (!(error instanceof ApiError) || !error.problem) return 1;
  const { templateCount } = error.problem as { templateCount?: unknown };
  return typeof templateCount === 'number' && templateCount > 0 ? templateCount : 1;
}

/** `max` is the name limit of the kind that was written, used when a field fails server validation. */
export function describeFailure(error: unknown, t: TFunction, max: number): TrendFailure {
  const code = trendProblemCode(error);
  const failure: TrendFailure = { code, message: undefined, fields: {} };
  if (code === 'category-in-use') {
    failure.message = t('trend.problems.category-in-use', { count: templateCountOf(error) });
  } else if (code === 'category-key-exhausted') {
    failure.fields.nameVi = t('trend.problems.category-key-exhausted');
  } else if (code === 'validation-failed') {
    const errors = error instanceof ApiError ? (error.problem?.errors ?? {}) : {};
    // The console checks length first, so a Vietnamese-name refusal that reaches the server is the letter-or-digit rule.
    if ('nameVi' in errors) failure.fields.nameVi = t('trend.dialog.errors.letterOrDigit');
    if ('nameEn' in errors) failure.fields.nameEn = t('trend.dialog.errors.name', { max });
    if (Object.keys(failure.fields).length === 0) {
      failure.message = t('trend.problems.validation-failed');
    }
  } else if (code !== undefined && GENERAL_CODES.has(code)) {
    failure.message = t(`trend.problems.${code}`);
  } else {
    failure.message = t('trend.common.saveFailed');
  }
  return failure;
}
