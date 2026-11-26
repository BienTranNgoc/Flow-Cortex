import {
  setToken,
  type BillingReceiptResponse,
  type TransactionDetailResponse,
} from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { ReceiptPage } from './ReceiptPage';
import { TransactionDetailPage } from './TransactionDetailPage';

const ID = '5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11';
const RECEIPT_PATH = `/api/v1/billing/transactions/${ID}/receipt`;
const PDF_PATH = `${RECEIPT_PATH}/pdf`;
const DETAIL_PATH = `/api/v1/billing/transactions/${ID}`;

const RECEIPT: BillingReceiptResponse = {
  transactionId: ID,
  orderRef: 'fc_0123456789abcdef0123456789abcdef',
  kind: 'topup',
  paidAt: '2026-12-31T17:05:00Z',
  provider: 'momo',
  instrument: 'wallet',
  providerTransactionId: '4088878653',
  seller: { legalName: null, taxCode: null, address: null, email: null },
  lines: [
    { description: 'Top-up 1,000 credits', quantity: 1, unitPriceVnd: 90909, amountVnd: 90909 },
  ],
  subtotalVnd: 90909,
  vatRateBps: 1000,
  vatAmountVnd: 9091,
  totalVnd: 100000,
  currency: 'VND',
  totalInWords: 'Một trăm nghìn đồng',
};

function payment(overrides: Partial<TransactionDetailResponse>): TransactionDetailResponse {
  return {
    id: ID,
    kind: 'topup',
    description: 'Top-up 1,000 credits',
    amountVnd: 100000,
    status: 'succeeded',
    openedAt: '2026-12-31T17:00:00Z',
    succeededAt: '2026-12-31T17:05:00Z',
    orderRef: RECEIPT.orderRef,
    provider: 'momo',
    instrument: 'wallet',
    providerPayType: null,
    providerTransactionId: '4088878653',
    outcomeCode: null,
    timeline: [],
    ...overrides,
  };
}

describe('ReceiptPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  const open = () =>
    renderRoute(
      '/billing/transactions/:transactionId/receipt',
      `/billing/transactions/${ID}/receipt`,
      <ReceiptPage />,
    );

  it('shows the stored split, the total in words and the provider placeholder', async () => {
    stubApi({ [`GET ${RECEIPT_PATH}`]: { status: 200, body: RECEIPT } });
    const { queryClient } = open();
    await settled(queryClient);

    expect(await screen.findByText('Payment successful')).toBeInTheDocument();
    expect(screen.getByText('Subtotal').nextSibling).toHaveTextContent('90,909 ₫');
    expect(screen.getByText('VAT (10%)').nextSibling).toHaveTextContent('9,091 ₫');
    expect(screen.getByText('Total').nextSibling).toHaveTextContent('100,000 ₫');
    expect(screen.getByText(/Một trăm nghìn đồng/)).toBeInTheDocument();
    expect(screen.getByText('Provider details are being set up.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeEnabled();
  });

  it('says a pending payment has no receipt yet and links to the payment', async () => {
    stubApi({
      [`GET ${RECEIPT_PATH}`]: {
        status: 409,
        body: { status: 409, code: 'billing_receipt_not_collected', detail: 'Not collected' },
      },
    });
    const { queryClient } = open();
    await settled(queryClient);

    expect(await screen.findByText(/has not been collected yet/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View payment' })).toHaveAttribute(
      'href',
      `/billing/transactions/${ID}`,
    );
  });

  it('keeps the receipt and shows an error when the PDF download fails', async () => {
    stubApi({
      [`GET ${RECEIPT_PATH}`]: { status: 200, body: RECEIPT },
      [`GET ${PDF_PATH}`]: { status: 500, body: { status: 500, detail: 'Boom' } },
    });
    const user = userEvent.setup();
    const { queryClient } = open();
    await settled(queryClient);

    await user.click(await screen.findByRole('button', { name: 'Download PDF' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'We could not download the receipt.',
    );
    expect(screen.getByText('Payment successful')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeEnabled();
  });
});

describe('TransactionDetailPage receipt link', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  const open = () =>
    renderRoute(
      '/billing/transactions/:transactionId',
      `/billing/transactions/${ID}`,
      <TransactionDetailPage />,
    );

  it('links a collected payment to its receipt', async () => {
    stubApi({ [`GET ${DETAIL_PATH}`]: { status: 200, body: payment({}) } });
    const { queryClient } = open();
    await settled(queryClient);

    expect(await screen.findByRole('link', { name: 'View receipt' })).toHaveAttribute(
      'href',
      `/billing/transactions/${ID}/receipt`,
    );
  });

  it('shows no receipt link for a failed payment', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: {
        status: 200,
        body: payment({ status: 'failed', succeededAt: null }),
      },
    });
    const { queryClient } = open();
    await settled(queryClient);

    expect(await screen.findByText('Payment details')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View receipt' })).not.toBeInTheDocument();
  });
});
