import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { WorkflowsListPage } from './WorkflowsListPage';

const PRODUCT_VIDEO_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

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

describe('WorkflowsListPage', () => {
  beforeEach(() => {
    setToken('t');
  });
  afterEach(() => {
    setToken(null);
  });

    it('hides the Product Video entry when the capabilities flag is off', async () => {
    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': {
        status: 200,
        body: { mediaEnabled: true, productVideoEnabled: false },
      },
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    // The default while the capabilities call is in flight is "visible" (the start endpoint will
    // 503 anyway). Wait for the workflow list to land, then for the flag to flip the entry off.
    expect(await screen.findByText('Check a video file')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText('Product Video')).not.toBeInTheDocument(),
    );
  });

  it('shows the Product Video entry when the capabilities flag is on', async () => {
    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': {
        status: 200,
        body: { mediaEnabled: true, productVideoEnabled: true },
      },
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Product Video')).toBeInTheDocument();
    expect(screen.getByText('Check a video file')).toBeInTheDocument();
  });
});
