import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../../test/fakeApi';
import type { CardModel } from './cardModel';
import { ConfirmCard } from './ConfirmCard';

const CONFIRM = 'POST /api/v1/chat/conversations/c1/cards/m1/confirm';

const IMAGE_CARD: CardModel = {
  kind: 'run',
  state: 'pending',
  workflowName: 'Photo polish',
  estimatedCredits: 12,
  uploads: [{ uploadId: 'u1', fileName: 'cup.png' }],
  input: '',
  product: { productName: '', description: '', cta: '', price: '', promotion: '' },
  runId: null,
};

const VIDEO_CARD: CardModel = {
  ...IMAGE_CARD,
  kind: 'product_video',
  workflowName: 'Product Video',
  product: { productName: 'Phin coffee', description: '', cta: '', price: '45k', promotion: 'Buy 2 get 1' },
};

function renderCard(card: CardModel) {
  return renderRoute('/chat/:id', '/chat/c1', <ConfirmCard conversationId="c1" messageId="m1" card={card} />);
}

describe('ConfirmCard', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists the image, the workflow and the estimated cost, and starts one run for a double click', async () => {
    const calls = stubApi({
      [CONFIRM]: { status: 200, body: { runId: 'r1' } },
      'GET /api/v1/chat/conversations/c1/messages': { status: 200, body: [] },
    });
    const { queryClient } = renderCard(IMAGE_CARD);
    await settled(queryClient);

    expect(screen.getByText('Photo polish')).toBeInTheDocument();
    expect(screen.getByText('cup.png')).toBeInTheDocument();
    expect(screen.getByText('Estimated cost: 12 credits')).toBeInTheDocument();

    const run = screen.getByRole('button', { name: 'Run' });
    await userEvent.dblClick(run);

    expect(await screen.findByText('Run started')).toBeInTheDocument();
    expect(calls.filter((call) => call.path.endsWith('/confirm'))).toHaveLength(1);
    expect(calls.find((call) => call.path.endsWith('/confirm'))?.body).toEqual({ acknowledgeCost: false });
  });

  it('shows the shortfall and starts nothing until Run anyway is chosen', async () => {
    let answers = 0;
    const calls = stubApi({
      [CONFIRM]: () =>
        answers++ === 0
          ? {
              status: 402,
              body: { status: 402, code: 'INSUFFICIENT_CREDITS', balance: 5, required: 12, shortfall: 7 },
            }
          : { status: 200, body: { runId: 'r1' } },
      'GET /api/v1/chat/conversations/c1/messages': { status: 200, body: [] },
    });
    const { queryClient } = renderCard(IMAGE_CARD);
    await settled(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Run' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('you have 5, this run needs 12 (short 7)');
    expect(calls.filter((call) => call.path.endsWith('/confirm'))).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: 'Run anyway' }));

    expect(await screen.findByText('Run started')).toBeInTheDocument();
    const confirms = calls.filter((call) => call.path.endsWith('/confirm'));
    expect(confirms.map((call) => call.body)).toEqual([{ acknowledgeCost: false }, { acknowledgeCost: true }]);
  });

  it('keeps Run off until the Product Video description has text, then sends the edited fields', async () => {
    const calls = stubApi({
      [CONFIRM]: { status: 200, body: { runId: 'r1' } },
      'GET /api/v1/chat/conversations/c1/messages': { status: 200, body: [] },
    });
    const { queryClient } = renderCard(VIDEO_CARD);
    await settled(queryClient);

    expect(screen.getByLabelText('Product name')).toHaveValue('Phin coffee');
    expect(screen.getByLabelText(/Price/)).toHaveValue('45k');
    const run = screen.getByRole('button', { name: 'Run' });
    expect(run).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Description'), 'Slow-drip Vietnamese coffee');
    expect(run).toBeEnabled();
    await userEvent.click(run);

    await waitFor(() => expect(calls.some((call) => call.path.endsWith('/confirm'))).toBe(true));
    expect(calls.find((call) => call.path.endsWith('/confirm'))?.body).toEqual({
      acknowledgeCost: false,
      productVideo: {
        productName: 'Phin coffee',
        description: 'Slow-drip Vietnamese coffee',
        price: '45k',
        promotion: 'Buy 2 get 1',
      },
    });
  });

  it('asks for new images when the stored ones expired and keeps Run off until one is ready', async () => {
    stubApi({});
    const { queryClient } = renderCard({ ...IMAGE_CARD, state: 'images-expired' });
    await settled(queryClient);

    expect(screen.getByRole('alert')).toHaveTextContent('Image expired — attach again');
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    expect(screen.getByLabelText('Attach images again')).toBeInTheDocument();
  });
});
