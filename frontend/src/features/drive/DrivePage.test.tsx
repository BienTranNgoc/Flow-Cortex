import { setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { DrivePage } from './DrivePage';

const CANDIDATE = {
  id: 'suggestion-1',
  kind: 'text',
  previewText: 'A caption worth keeping',
  textValue: 'A caption worth keeping',
  tooLong: false,
  workflowName: 'Echo',
  runId: 'run-1',
  at: '2026-11-20T08:00:00Z',
};

const ROUTES = {
  'GET /api/v1/drive/items': { status: 200, body: { items: [], total: 0, page: 0, size: 20 } },
  'GET /api/v1/drive/suggestions': { status: 200, body: [CANDIDATE] },
  'GET /api/v1/drive/memory': {
    status: 200,
    body: { items: [], total: 0, page: 0, pageSize: 20, pinned: [], sizeBuckets: { lt1mb: 0, '1to50mb': 0, gt50mb: 0 } },
  },
  'POST /api/v1/drive/items': { status: 201, body: { id: 'item-1' } },
};

describe('DrivePage kept suggestions', () => {
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

  it('keeps a suggestion marked as Kept after switching to Memory and back, without a second item', async () => {
    const calls = stubApi(ROUTES);
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/drive', '/drive', <DrivePage />);
    await settled(queryClient);

    await user.click(await screen.findByRole('button', { name: 'Keep Text from Echo' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Keep' }));
    expect(await screen.findByRole('button', { name: 'Kept' })).toHaveAttribute('aria-disabled', 'true');

    await user.click(screen.getByRole('tab', { name: 'Memory' }));
    await settled(queryClient);
    expect(screen.queryByRole('button', { name: 'Kept' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Items' }));
    await settled(queryClient);

    const kept = await screen.findByRole('button', { name: 'Kept' });
    await user.click(kept);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Keep Text from Echo' })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/drive/items')).toHaveLength(1);
  });
});
