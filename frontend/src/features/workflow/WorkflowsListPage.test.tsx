import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { WorkflowsListPage } from './WorkflowsListPage';

const PRODUCT_VIDEO_ID = 'pv';

const CHECK: WorkflowResponse = {
  id: 'w1',
  name: 'Check a video file',
  isShared: false,
  steps: [],
};

const PRODUCT_VIDEO: WorkflowResponse = {
  id: PRODUCT_VIDEO_ID,
  name: 'Product Video',
  isShared: true,
  steps: [],
};

function capabilities(productVideoEnabled: boolean): FakeReply {
  return {
    status: 200,
    body: { mediaEnabled: true, productVideoEnabled, productVideoWorkflowId: PRODUCT_VIDEO_ID },
  };
}

describe('WorkflowsListPage', () => {
  beforeEach(() => {
    setToken('t', false);
  });
  afterEach(() => {
    setToken(null);
  });

  it('hides the workflow the capabilities name as Product Video when its flag is off', async () => {
    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(false),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Check a video file')).toBeInTheDocument();
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();
  });

  it('never shows a switched-off Product Video entry while the capabilities are loading', async () => {
    let answer: (reply: FakeReply) => void = () => {};
    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': () => new Promise<FakeReply>((resolve) => (answer = resolve)),
    });
    const { queryClient } = renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    await waitFor(() => expect(queryClient.isFetching()).toBe(1));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();

    answer(capabilities(false));
    expect(await screen.findByText('Check a video file')).toBeInTheDocument();
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();
  });

  it('shows the Product Video entry when the capabilities flag is on', async () => {
    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Product Video')).toBeInTheDocument();
    expect(screen.getByText('Check a video file')).toBeInTheDocument();
  });
});
