import { setToken, type WorkflowReportDetailResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { WorkflowReportDetailPage } from './WorkflowReportDetailPage';

const REPORT_ID = '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b';
const ME_PATH = 'GET /api/v1/auth/me';
const REPORT_PATH = `GET /api/v1/admin/workflow-reports/${REPORT_ID}`;
const HIDE_PATH = `POST /api/v1/admin/workflow-reports/${REPORT_ID}/hide`;

function report(overrides: Partial<WorkflowReportDetailResponse['workflow']> = {}): WorkflowReportDetailResponse {
  return {
    id: REPORT_ID,
    reason: 'spam',
    details: 'Posts the same offer again and again',
    status: 'pending',
    createdAt: '2026-11-20T09:00:00Z',
    reporter: { id: 'u-2', name: 'Lan', email: 'lan@flowcortex.dev' },
    workflow: {
      id: 'w-1',
      name: 'Resize video',
      status: 'published',
      visibility: 'public',
      moderationStatus: 'visible',
      deleted: false,
      owner: { id: 'u-1', name: 'Mai', email: 'mai@flowcortex.dev' },
      ...overrides,
    },
    history: [],
  };
}

function render() {
  return renderRoute('/workflow-reports/:reportId', `/workflow-reports/${REPORT_ID}`, <WorkflowReportDetailPage />);
}

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-workflow-report', true);
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

describe('WorkflowReportDetailPage', () => {
  it('sends no request while the required reason of a hide is blank', async () => {
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [REPORT_PATH]: { status: 200, body: report() },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason (required)'), '   ');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Hide workflow' }));

    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('hides the workflow with the reason and note, then offers restore, dismiss and resolve', async () => {
    let current = report();
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [REPORT_PATH]: () => ({ status: 200, body: current }),
      [HIDE_PATH]: () => {
        current = { ...report({ moderationStatus: 'hidden' }), status: 'hidden', resolution: 'hide' };
        return { status: 200, body: current };
      },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason (required)'), ' Repeated spam ');
    await userEvent.type(within(dialog).getByLabelText('Internal note (optional)'), 'seen twice');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Hide workflow' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      reason: 'Repeated spam',
      internalNote: 'seen twice',
    });
    expect(await screen.findByRole('button', { name: 'Restore' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hide' })).not.toBeInTheDocument();
  });

  it('keeps the dialog open with the server message when the action is refused', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [REPORT_PATH]: { status: 200, body: report() },
      [HIDE_PATH]: { status: 422, body: { status: 422, detail: 'The reason is too long' } },
    });
    render();

    await userEvent.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason (required)'), 'spam');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Hide workflow' }));

    expect(await within(dialog).findByText('The reason is too long')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('offers only dismiss and resolve to a caller without the hide key', async () => {
    stubApi({
      [ME_PATH]: {
        status: 200,
        body: staffMe('CUSTOMER_SUPPORT', ['moderation.report.view', 'moderation.report.resolve']),
      },
      [REPORT_PATH]: { status: 200, body: report() },
    });
    render();

    await screen.findByRole('button', { name: 'Dismiss' });
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hide' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  it('offers only dismiss and resolve when the workflow was deleted', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [REPORT_PATH]: { status: 200, body: report({ deleted: true, moderationStatus: 'hidden' }) },
    });
    render();

    await screen.findByRole('button', { name: 'Dismiss' });
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restore' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hide' })).not.toBeInTheDocument();
  });

  it('shows the not-found state for an unknown report', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [REPORT_PATH]: { status: 404, body: { status: 404, detail: 'Report not found' } },
    });
    render();

    expect(await screen.findByRole('heading', { level: 1, name: 'Report not found' })).toBeInTheDocument();
  });
});
