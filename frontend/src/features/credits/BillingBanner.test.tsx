import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { BillingBanner } from './BillingBanner';

const FEATURES = { credits: true };

function renderBanner() {
  return renderRoute('/', '/', <BillingBanner />);
}

describe('BillingBanner', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('shows balance, credits used and the tier without a warning when plenty is left', async () => {
    stubApi({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 800, granted: 1000 } },
    });

    renderBanner();

    expect(await screen.findByText('800 credits')).toBeInTheDocument();
    expect(screen.getByText('200 of 1,000 credits used')).toBeInTheDocument();
    expect(screen.getByText('balance healthy')).toBeInTheDocument();
    expect(screen.queryByText(/running low|critically low|no credits left\. Runs/)).not.toBeInTheDocument();
  });

  it('adds a warning line when the balance is critical', async () => {
    stubApi({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 30, granted: 1000 } },
    });

    renderBanner();

    expect(await screen.findByText('balance critically low')).toBeInTheDocument();
    expect(screen.getByText(/Runs may be refused soon/)).toBeInTheDocument();
  });

  it('renders nothing while credits are off', async () => {
    stubApi({ 'GET /api/v1/features': { status: 200, body: { credits: false } } });

    const { container, queryClient } = renderBanner();
    await settled(queryClient);

    expect(container).toBeEmptyDOMElement();
  });
});
