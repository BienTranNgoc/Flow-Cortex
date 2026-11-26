import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { RefundBlock } from './RefundBlock';

const ID = '3f2b8c1e-5a4d-4e7b-9c10-0a1b2c3d4e5f';
const ELIGIBILITY = `GET /api/v1/billing/transactions/${ID}/refund-eligibility`;
const REQUEST = `POST /api/v1/billing/transactions/${ID}/refund-request`;
const CANCEL = `POST /api/v1/billing/transactions/${ID}/refund-request/cancel`;

const TURNS = { enabled: true, used: 0, limit: 2, windowMonths: 6, nextTurnAt: null };

function right(overrides: Record<string, unknown> = {}) {
  return {
    transactionId: ID,
    amountVnd: 199000,
    currency: 'VND',
    kind: 'plan_purchase',
    cycle: 'monthly',
    windowDays: 3,
    deadlineAt: '2026-12-04T10:00:00Z',
    daysRemaining: 3,
    verdict: 'permitted',
    refusal: null,
    ...overrides,
  };
}

function eligibility(overrides: Record<string, unknown> = {}): FakeReply {
  return {
    status: 200,
    body: {
      right: right(),
      turns: TURNS,
      paidAt: '2026-12-01T10:00:00Z',
      daysElapsed: 0,
      request: null,
      cancellable: false,
      ...overrides,
    },
  };
}

const PENDING = {
  id: 'r1',
  code: 'HT-000042',
  status: 'pending',
  reason: 'Tôi mua nhầm gói tháng.',
  requestedAt: '2026-12-01T11:00:00Z',
  decidedAt: null,
  decisionNote: null,
  processingAt: null,
  rejectedAt: null,
  rejectionReason: null,
};

const REASON = 'I bought the wrong plan.';

function renderBlock() {
  return renderRoute('/p', '/p', <RefundBlock transactionId={ID} />);
}

describe('RefundBlock', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    setToken('t', true);
    restoreDialog = stubModalDialog();
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('links to the policy in a new tab', async () => {
    stubApi({ [ELIGIBILITY]: eligibility() });

    renderBlock();

    const policy = await screen.findByRole('link', { name: /Refund policy/ });
    expect(policy).toHaveAttribute('href', '/legal/termination-refund');
    expect(policy).toHaveAttribute('target', '_blank');
  });

  it('keeps Send disabled until the reason holds ten characters, then sends it and shows the pending request', async () => {
    let sent = false;
    const calls = stubApi({
      [ELIGIBILITY]: () =>
        sent ? eligibility({ request: PENDING, cancellable: true }) : eligibility(),
      [REQUEST]: () => {
        sent = true;
        return { status: 200, body: PENDING };
      },
    });
    const user = userEvent.setup();
    renderBlock();

    await user.click(await screen.findByRole('button', { name: 'Request a refund' }));
    const send = screen.getByRole('button', { name: 'Send request' });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText('Reason'), 'too short');
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText('Reason'), REASON);
    expect(send).toBeEnabled();
    await user.click(send);

    expect(
      await screen.findByText('Your refund request HT-000042 is being handled.'),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: 'Cancel request' })).toBeVisible();
    expect(document.querySelector('dialog[open]')).toBeNull();
    expect(calls.find((call) => call.path.endsWith('/refund-request'))?.body).toEqual({
      reason: `too short${REASON}`,
    });
  });

  it('closes the dialog and shows the refusal when the right ended while it was open', async () => {
    let refused = false;
    stubApi({
      [ELIGIBILITY]: () =>
        refused
          ? eligibility({
              right: right({ verdict: 'refused', refusal: 'window_elapsed', daysRemaining: 0 }),
            })
          : eligibility(),
      [REQUEST]: () => {
        refused = true;
        return {
          status: 409,
          body: { status: 409, code: 'refund_not_eligible', refusal: 'window_elapsed' },
        };
      },
    });
    const user = userEvent.setup();
    renderBlock();

    await user.click(await screen.findByRole('button', { name: 'Request a refund' }));
    await user.type(screen.getByLabelText('Reason'), REASON);
    await user.click(screen.getByRole('button', { name: 'Send request' }));

    expect(
      await screen.findByText('The 3-day window to ask for a refund on this payment has ended.'),
    ).toBeVisible();
    expect(document.querySelector('dialog[open]')).toBeNull();
  });

  it('keeps the dialog open with a failure line and the reason when the server fails', async () => {
    stubApi({
      [ELIGIBILITY]: eligibility(),
      [REQUEST]: { status: 500, body: { status: 500, detail: 'boom' } },
    });
    const user = userEvent.setup();
    renderBlock();

    await user.click(await screen.findByRole('button', { name: 'Request a refund' }));
    await user.type(screen.getByLabelText('Reason'), REASON);
    await user.click(screen.getByRole('button', { name: 'Send request' }));

    expect(
      await screen.findByText('We could not send your request. Your text is kept; try again.'),
    ).toBeVisible();
    expect(document.querySelector('dialog[open]')).not.toBeNull();
    expect(screen.getByLabelText('Reason')).toHaveValue(REASON);
  });

  it('returns focus to the request button when Escape closes the dialog', async () => {
    stubApi({ [ELIGIBILITY]: eligibility() });
    const user = userEvent.setup();
    renderBlock();
    const open = await screen.findByRole('button', { name: 'Request a refund' });

    await user.click(open);
    expect(screen.getByLabelText('Reason')).toHaveFocus();
    await user.keyboard('{Escape}');

    await waitFor(() => expect(document.querySelector('dialog[open]')).toBeNull());
    expect(open).toHaveFocus();
  });

  it('shows that a request already being handled cannot be cancelled', async () => {
    stubApi({
      [ELIGIBILITY]: eligibility({ request: PENDING, cancellable: true }),
      [CANCEL]: {
        status: 409,
        body: { status: 409, code: 'refund_request_not_cancellable' },
      },
    });
    const user = userEvent.setup();
    renderBlock();

    await user.click(await screen.findByRole('button', { name: 'Cancel request' }));

    expect(
      await screen.findByText(
        'This request is already being handled and can no longer be cancelled.',
      ),
    ).toBeVisible();
    expect(screen.getByRole('status')).toBeVisible();
  });
});
