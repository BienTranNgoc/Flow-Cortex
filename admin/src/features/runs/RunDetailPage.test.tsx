import { setToken, type AdminRunDetailResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { RunDetailPage } from './RunDetailPage';

const RUN_ID = '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b';
const ME_PATH = 'GET /api/v1/auth/me';
const RUN_PATH = `GET /api/v1/admin/runs/${RUN_ID}`;
const CANCEL_PATH = `POST /api/v1/admin/runs/${RUN_ID}/cancel`;

function run(overrides: Partial<AdminRunDetailResponse> = {}): AdminRunDetailResponse {
  return {
    id: RUN_ID,
    workflowId: 'w-1',
    workflowName: 'Resize video',
    ownerId: 'u-1',
    ownerEmail: 'mai@flowcortex.dev',
    status: 'running',
    startedAt: '2026-11-01T10:00:00Z',
    creditsCharged: 30,
    steps: [
      { stepOrder: 1, type: 'ffmpeg', mode: 'remote', status: 'running', attempt: 1, deliveryCount: 1 },
      {
        stepOrder: 2,
        type: 'upload',
        mode: 'local',
        status: 'error',
        attempt: 3,
        deliveryCount: 3,
        errorCode: 'lease_expired',
        errorMessage: 'Lease expired',
      },
    ],
    ...overrides,
  };
}

function render() {
  return renderRoute('/runs/:runId', `/runs/${RUN_ID}`, <RunDetailPage />);
}

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-run-detail', true);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    },
  });
});
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  for (const [name, descriptor] of [['showModal', showModal], ['close', close]] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
  }
});

describe('RunDetailPage', () => {
  it('stops the run after the confirmation and shows it as cancelling', async () => {
    let current = run();
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [RUN_PATH]: () => ({ status: 200, body: current }),
      [CANCEL_PATH]: () => {
        current = run({ status: 'cancelling' });
        return { status: 200, body: current };
      },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Stop run' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Stop run' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.filter((call) => call.path.endsWith('/cancel'))).toHaveLength(1);
    expect(await screen.findByText('Cancelling')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Stop run' })).not.toBeInTheDocument();
  });

  it('changes nothing when the confirmation is answered "Keep running"', async () => {
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [RUN_PATH]: { status: 200, body: run() },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Stop run' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Keep running' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
    expect(screen.getByRole('button', { name: 'Stop run' })).toBeInTheDocument();
  });

  it('keeps the dialog open with the server message when the run finished meanwhile', async () => {
    let current = run();
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [RUN_PATH]: () => ({ status: 200, body: current }),
      [CANCEL_PATH]: () => {
        current = run({ status: 'completed' });
        return { status: 409, body: { status: 409, detail: 'This run has already finished' } };
      },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Stop run' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Stop run' }));

    expect(await screen.findByText('This run has already finished')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('offers no stop for a finished run, or to a caller without the stop key', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [RUN_PATH]: { status: 200, body: run({ status: 'completed' }) },
    });
    const finished = render();
    await screen.findByRole('heading', { level: 1, name: 'Resize video' });
    expect(screen.queryByRole('button', { name: 'Stop run' })).not.toBeInTheDocument();
    finished.unmount();

    stubApi({
      [ME_PATH]: { status: 200, body: staffMe('PLATFORM_OPERATOR', ['workflow.run.view']) },
      [RUN_PATH]: { status: 200, body: run() },
    });
    render();
    await screen.findByRole('heading', { level: 1, name: 'Resize video' });
    expect(screen.queryByRole('button', { name: 'Stop run' })).not.toBeInTheDocument();
  });
});
