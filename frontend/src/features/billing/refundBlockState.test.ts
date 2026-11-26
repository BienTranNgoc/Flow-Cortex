import { describe, expect, it } from 'vitest';
import { refundBlockState, refusalSentence, type RefundBlockInput } from './refundBlockState';

const TURNS = { limit: 2, windowMonths: 6, nextTurnAt: null };

function input(overrides: {
  verdict?: 'permitted' | 'refused';
  refusal?: string | null;
  daysRemaining?: number;
  windowDays?: number;
  request?: RefundBlockInput['request'];
  cancellable?: boolean;
  turns?: RefundBlockInput['turns'];
}): RefundBlockInput {
  return {
    right: {
      verdict: overrides.verdict ?? 'permitted',
      refusal: (overrides.refusal ?? null) as RefundBlockInput['right']['refusal'],
      daysRemaining: overrides.daysRemaining ?? 3,
      windowDays: overrides.windowDays ?? 3,
    },
    turns: overrides.turns ?? TURNS,
    request: overrides.request ?? null,
    cancellable: overrides.cancellable ?? false,
  };
}

const REQUEST = {
  code: 'HT-000042',
  requestedAt: '2026-12-01T10:00:00Z',
  rejectionReason: null,
};

describe('refundBlockState', () => {
  it('shows nothing while the eligibility is not loaded or failed to load', () => {
    expect(refundBlockState(undefined)).toEqual({ shape: 'hidden' });
    expect(refundBlockState(null)).toEqual({ shape: 'hidden' });
  });

  it('lets a pending request win over a verdict whose window has elapsed', () => {
    const state = refundBlockState(
      input({
        verdict: 'refused',
        refusal: 'window_elapsed',
        request: { ...REQUEST, status: 'pending' },
        cancellable: true,
      }),
    );

    expect(state).toEqual({
      shape: 'processing',
      code: 'HT-000042',
      requestedAt: '2026-12-01T10:00:00Z',
      cancellable: true,
    });
  });

  it('shows an approved request as processing without a cancel', () => {
    for (const status of ['approved', 'auto_approved'] as const) {
      expect(
        refundBlockState(
          input({
            verdict: 'refused',
            refusal: 'already_refunded',
            request: { ...REQUEST, status },
            cancellable: false,
          }),
        ),
      ).toEqual({
        shape: 'processing',
        code: 'HT-000042',
        requestedAt: '2026-12-01T10:00:00Z',
        cancellable: false,
      });
    }
  });

  it('shows a rejected request with its code and reason', () => {
    const state = refundBlockState(
      input({
        verdict: 'refused',
        refusal: 'previously_rejected',
        request: { ...REQUEST, status: 'rejected', rejectionReason: 'Đã dùng dịch vụ' },
      }),
    );

    expect(state).toEqual({ shape: 'rejected', code: 'HT-000042', reason: 'Đã dùng dịch vụ' });
  });

  it('falls through a cancelled request to the verdict', () => {
    expect(
      refundBlockState(input({ request: { ...REQUEST, status: 'cancelled' }, daysRemaining: 2 })),
    ).toEqual({ shape: 'invite', daysRemaining: 2 });
  });

  it('invites with the days left while the right is permitted', () => {
    expect(refundBlockState(input({ daysRemaining: 3 }))).toEqual({
      shape: 'invite',
      daysRemaining: 3,
    });
    expect(refundBlockState(input({ daysRemaining: 1 }))).toEqual({
      shape: 'invite',
      daysRemaining: 1,
    });
  });

  it('closes with the window sentence once the window has elapsed', () => {
    expect(
      refundBlockState(
        input({ verdict: 'refused', refusal: 'window_elapsed', windowDays: 7, daysRemaining: 0 }),
      ),
    ).toEqual({
      shape: 'closed',
      sentence: { key: 'billing.refund.refused.window_elapsed', values: { days: 7 } },
    });
  });

  it('closes with the turn limit, the months and the next date', () => {
    const turns = { limit: 2, windowMonths: 6, nextTurnAt: '2027-02-28T08:00:00Z' };

    expect(
      refundBlockState(input({ verdict: 'refused', refusal: 'turn_limit_reached', turns })),
    ).toEqual({
      shape: 'closed',
      sentence: {
        key: 'billing.refund.refused.turn_limit_reached',
        values: { limit: 2, months: 6, nextTurnAt: '2027-02-28T08:00:00Z' },
      },
    });
  });

  it('closes with the no-date sentence when no next turn is known', () => {
    expect(refundBlockState(input({ verdict: 'refused', refusal: 'turn_limit_reached' }))).toEqual({
      shape: 'closed',
      sentence: {
        key: 'billing.refund.refused.turn_limit_reached_no_date',
        values: { limit: 2, months: 6 },
      },
    });
  });
});

describe('refusalSentence', () => {
  it.each(['not_settled', 'already_refunded', 'previously_rejected'] as const)(
    'maps %s to its own key',
    (refusal) => {
      expect(refusalSentence(refusal, 3, TURNS)).toEqual({
        key: `billing.refund.refused.${refusal}`,
        values: {},
      });
    },
  );

  it('answers the generic sentence for a refusal it does not know', () => {
    expect(refusalSentence('some_future_refusal', 3, TURNS)).toEqual({
      key: 'billing.refund.refused.generic',
      values: {},
    });
    expect(refusalSentence(null, 3, TURNS)).toEqual({
      key: 'billing.refund.refused.generic',
      values: {},
    });
  });
});
