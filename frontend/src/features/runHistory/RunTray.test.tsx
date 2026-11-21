import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { RunTray } from './RunTray';

const RUNNING = {
  id: 'r1',
  workflowId: 'w',
  workflowName: 'Dub',
  status: 'running',
  favorite: false,
  startedAt: '2026-11-08T10:00:00Z',
};
const FINISHED = { ...RUNNING, id: 'r2', status: 'completed', title: 'Done cut' };

describe('RunTray', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('closes on Escape and gives focus back to the button', async () => {
    stubApi({ 'GET /api/v1/runs/tray': { status: 200, body: { active: [], recent: [FINISHED] } } });
    const user = userEvent.setup();
    renderRoute('/', '/', <RunTray />);

    const button = await screen.findByRole('button', { name: 'Runs' });
    await user.click(button);
    expect(screen.getByRole('link', { name: 'View all runs' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('link', { name: 'View all runs' })).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it('closes on an outside click and leaves focus where the member clicked', async () => {
    stubApi({ 'GET /api/v1/runs/tray': { status: 200, body: { active: [], recent: [] } } });
    const user = userEvent.setup();
    renderRoute('/', '/', <div><RunTray /><button type="button">elsewhere</button></div>);

    const button = await screen.findByRole('button', { name: 'Runs' });
    await user.click(button);
    await user.click(screen.getByRole('button', { name: 'elsewhere' }));
    expect(screen.queryByRole('link', { name: 'View all runs' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'elsewhere' })).toHaveFocus();
  });

  it('shows no count when the tray request fails', async () => {
    stubApi({ 'GET /api/v1/runs/tray': { status: 500, body: { status: 500 } } });
    const { queryClient } = renderRoute('/', '/', <RunTray />);

    expect(await screen.findByRole('button', { name: 'Runs' })).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByText('1')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Runs' })).toBeInTheDocument();
  });
});
