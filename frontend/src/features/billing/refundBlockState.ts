import type { RefundRequestView, RightView, TurnsView } from '@flow-cortex/api-client';

/** The part of the eligibility answer the block is decided from. */
export interface RefundBlockInput {
  readonly right: Pick<RightView, 'verdict' | 'refusal' | 'daysRemaining' | 'windowDays'>;
  readonly turns: Pick<TurnsView, 'limit' | 'windowMonths' | 'nextTurnAt'>;
  readonly request: Pick<
    RefundRequestView,
    'status' | 'code' | 'requestedAt' | 'rejectionReason'
  > | null;
  readonly cancellable: boolean;
}

/** One locale key plus the values it interpolates; `nextTurnAt` is an ISO instant the view formats. */
export interface RefusalSentence {
  readonly key: string;
  readonly values: {
    readonly days?: number;
    readonly limit?: number;
    readonly months?: number;
    readonly nextTurnAt?: string;
  };
}

export type RefundBlockState =
  | { readonly shape: 'hidden' }
  | {
      readonly shape: 'processing';
      readonly code: string;
      readonly requestedAt: string;
      readonly cancellable: boolean;
    }
  | { readonly shape: 'rejected'; readonly code: string; readonly reason: string | null }
  | { readonly shape: 'closed'; readonly sentence: RefusalSentence }
  | { readonly shape: 'invite'; readonly daysRemaining: number };

const IN_HANDS: readonly string[] = ['pending', 'approved', 'auto_approved'];
const REFUSED_KEY = 'billing.refund.refused';
const PLAIN_REFUSALS: readonly string[] = [
  'not_settled',
  'already_refunded',
  'previously_rejected',
];

/**
 * The sentence for a refusal. A refusal this app does not know (a newer backend) reads as the generic one,
 * never as a raw code.
 */
export function refusalSentence(
  refusal: string | null,
  windowDays: number,
  turns: RefundBlockInput['turns'],
): RefusalSentence {
  if (refusal === 'window_elapsed') {
    return { key: `${REFUSED_KEY}.window_elapsed`, values: { days: windowDays } };
  }
  if (refusal === 'turn_limit_reached') {
    const counted = { limit: turns.limit, months: turns.windowMonths };
    return turns.nextTurnAt
      ? {
          key: `${REFUSED_KEY}.turn_limit_reached`,
          values: { ...counted, nextTurnAt: turns.nextTurnAt },
        }
      : { key: `${REFUSED_KEY}.turn_limit_reached_no_date`, values: counted };
  }
  if (refusal !== null && PLAIN_REFUSALS.includes(refusal)) {
    return { key: `${REFUSED_KEY}.${refusal}`, values: {} };
  }
  return { key: `${REFUSED_KEY}.generic`, values: {} };
}

/**
 * The one arbiter of the refund block: a request in hand (pending, approved) shows as processing whatever the
 * verdict says, a rejected one shows its reason, then a refusal closes the block and a permitted verdict
 * invites. A cancelled request holds nothing, so it falls through to the verdict.
 */
export function refundBlockState(input: RefundBlockInput | null | undefined): RefundBlockState {
  if (!input) return { shape: 'hidden' };
  const { request } = input;
  if (request && IN_HANDS.includes(request.status)) {
    return {
      shape: 'processing',
      code: request.code,
      requestedAt: request.requestedAt,
      cancellable: input.cancellable && request.status === 'pending',
    };
  }
  if (request?.status === 'rejected') {
    return { shape: 'rejected', code: request.code, reason: request.rejectionReason };
  }
  if (input.right.verdict === 'refused') {
    return {
      shape: 'closed',
      sentence: refusalSentence(input.right.refusal, input.right.windowDays, input.turns),
    };
  }
  return { shape: 'invite', daysRemaining: input.right.daysRemaining };
}
