import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type Handler } from '../../test/fakeApi';
import { ApprovalPanel } from './ApprovalPanel';

const APPROVE = 'POST /api/v1/review-tasks/t1/approve';
const REJECT = 'POST /api/v1/review-tasks/t1/reject';
const TASK = {
  id: 't1',
  runId: 'r1',
  runStepId: 's2',
  reviewType: 'APPROVAL_CHECKPOINT',
  status: 'pending',
  originalPayload: 'Script v1',
};

function renderPanel(decisions: Record<string, Handler> = {}) {
  const calls = stubApi({
    'GET /api/v1/runs/r1/review-tasks': { status: 200, body: [TASK] },
    ...decisions,
  });
  renderRoute('/', '/', <ApprovalPanel runId="r1" />);
  return calls;
}

function refusal(status: number, detail: string, extra: Record<string, unknown> = {}) {
  return { status, body: { status, detail, ...extra } };
}

describe('ApprovalPanel', () => {
  beforeEach(() => {
    setToken('t', true);
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('refuses a cleared text box at the field without sending anything', async () => {
    const user = userEvent.setup();
    const calls = renderPanel({
      [APPROVE]: { status: 200, body: { ...TASK, status: 'approved' } },
    });

    const content = await screen.findByLabelText('Content to approve');
    await user.clear(content);
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByText("Content can't be empty")).toBeInTheDocument();
    expect(content).toHaveAttribute('aria-invalid', 'true');
    expect(content).toHaveAccessibleDescription("Content can't be empty");
    expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(false);
  });

  it('approves an empty original as is, since only an edit can be empty', async () => {
    const user = userEvent.setup();
    const calls = stubApi({
      'GET /api/v1/runs/r1/review-tasks': { status: 200, body: [{ ...TASK, originalPayload: '' }] },
      [APPROVE]: { status: 200, body: { ...TASK, originalPayload: '', status: 'approved' } },
    });
    renderRoute('/', '/', <ApprovalPanel runId="r1" />);

    await screen.findByLabelText('Content to approve');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(true));
    expect(screen.queryByText("Content can't be empty")).not.toBeInTheDocument();
  });

  it('refuses text that is only whitespace, and drops the message once the text changes', async () => {
    const user = userEvent.setup();
    const calls = renderPanel();

    const content = await screen.findByLabelText('Content to approve');
    await user.clear(content);
    await user.type(content, '   ');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByText("Content can't be empty")).toBeInTheDocument();
    expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(false);

    await user.type(content, 'Script v2');
    expect(screen.queryByText("Content can't be empty")).not.toBeInTheDocument();
    expect(content).not.toHaveAttribute('aria-invalid');
  });

  it('shows the server field message when the server refuses the edited content', async () => {
    const user = userEvent.setup();
    renderPanel({
      [APPROVE]: refusal(422, 'Validation failed', {
        errors: { reviewedPayload: "Content can't be empty" },
      }),
    });

    const content = await screen.findByLabelText('Content to approve');
    await user.type(content, ' v2');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByText("Content can't be empty")).toBeInTheDocument();
    expect(content).toHaveAttribute('aria-invalid', 'true');
  });

  it('says the member cannot decide when the server answers 403', async () => {
    const user = userEvent.setup();
    renderPanel({ [APPROVE]: refusal(403, "You can't decide on this run") });

    await screen.findByLabelText('Content to approve');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent("You can't decide on this run");
  });

  it('says the run is being stopped when the server refuses a stopping run', async () => {
    const user = userEvent.setup();
    renderPanel({
      [APPROVE]: refusal(422, 'This run is being stopped', { code: 'RUN_STOPPING' }),
    });

    await screen.findByLabelText('Content to approve');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This run is being stopped');
  });

  it('says the step is no longer waiting when another decision won', async () => {
    const user = userEvent.setup();
    renderPanel({ [APPROVE]: refusal(409, 'This step is no longer waiting for review') });

    await screen.findByLabelText('Content to approve');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This step is no longer waiting for review',
    );
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveAttribute('aria-busy', 'false');
  });

  it('shows the server reason for a refused rejection and falls back when there is none', async () => {
    const user = userEvent.setup();
    let reply = refusal(403, "You can't decide on this run");
    const calls = renderPanel({ [REJECT]: () => reply });

    await screen.findByLabelText('Content to approve');
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    expect(await screen.findByRole('alert')).toHaveTextContent("You can't decide on this run");

    reply = { status: 500, body: { status: 500, detail: 'boom' } };
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    await waitFor(() =>
      expect(calls.filter((call) => call.path.endsWith('/reject'))).toHaveLength(2),
    );
    expect(await screen.findByText('Could not reject this step')).toBeInTheDocument();
  });
});
