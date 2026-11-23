import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type Handler } from '../../test/fakeApi';
import { ApprovalPanel } from './ApprovalPanel';

const APPROVE = 'POST /api/v1/review-tasks/t1/approve';
const REJECT = 'POST /api/v1/review-tasks/t1/reject';
const ANSWERS = 'POST /api/v1/review-tasks/t1/answers';
const TASK = {
  id: 't1',
  runId: 'r1',
  runStepId: 's2',
  reviewType: 'APPROVAL_CHECKPOINT',
  status: 'pending',
  originalPayload: 'Script v1',
  expiresAt: '2026-12-08T10:00:00Z',
};
const PICK_TASK = {
  ...TASK,
  originalPayload: '[{"title":"Hook A","body":"A body"},{"title":"Hook B","body":"B body"}]',
  pickOptions: [
    { title: 'Hook A', body: 'A body' },
    { title: 'Hook B', body: 'B body' },
  ],
};
const QUESTIONS = [
  { id: 'q1', type: 'single', question: 'Who is the audience?', options: ['Just me', 'Colleagues'] },
  { id: 'q2', type: 'multi', question: 'Which channels?', options: ['Email', 'Chat'] },
  { id: 'q3', type: 'text', question: 'Any constraints?', options: [] },
];
const QA_TASK = {
  ...TASK,
  reviewType: 'QA_REVIEW',
  questionsState: 'ready',
  questions: QUESTIONS,
};

function renderPanel(decisions: Record<string, Handler> = {}, task: object = TASK) {
  const calls = stubApi({
    'GET /api/v1/runs/r1/review-tasks': { status: 200, body: [task] },
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

  describe('a checkpoint with options', () => {
    it('keeps Approve disabled until an option is chosen', async () => {
      const user = userEvent.setup();
      renderPanel({}, PICK_TASK);

      const group = await screen.findByRole('radiogroup', { name: 'Choose one option' });
      expect(within(group).getAllByRole('radio')).toHaveLength(3);
      expect(within(group).getByRole('radio', { name: /Hook A/ })).not.toBeChecked();
      expect(within(group).getByRole('radio', { name: 'My own direction' })).toBeInTheDocument();
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();

      await user.click(screen.getByRole('radio', { name: /Hook B/ }));
      expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
    });

    it('sends pickedOption for a chosen option', async () => {
      const user = userEvent.setup();
      const calls = renderPanel(
        { [APPROVE]: { status: 200, body: { ...PICK_TASK, status: 'approved' } } },
        PICK_TASK,
      );

      await user.click(await screen.findByRole('radio', { name: /Hook B/ }));
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      await waitFor(() => expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(true));
      expect(calls.find((call) => call.path.endsWith('/approve'))?.body).toEqual({
        pickedOption: 1,
      });
    });

    it('sends my own direction as the reviewed content and no pickedOption', async () => {
      const user = userEvent.setup();
      const calls = renderPanel(
        { [APPROVE]: { status: 200, body: { ...PICK_TASK, status: 'approved' } } },
        PICK_TASK,
      );

      await user.click(await screen.findByRole('radio', { name: 'My own direction' }));
      await user.type(screen.getByRole('textbox', { name: 'My own direction' }), 'Make it funnier');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      await waitFor(() => expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(true));
      expect(calls.find((call) => call.path.endsWith('/approve'))?.body).toEqual({
        reviewedPayload: 'Make it funnier',
      });
    });

    it('refuses a blank own direction without sending', async () => {
      const user = userEvent.setup();
      const calls = renderPanel({}, PICK_TASK);

      await user.click(await screen.findByRole('radio', { name: 'My own direction' }));
      const own = screen.getByRole('textbox', { name: 'My own direction' });
      await user.type(own, '   ');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByText("Content can't be empty")).toBeInTheDocument();
      expect(own).toHaveAttribute('aria-invalid', 'true');
      expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(false);
    });
  });

  describe('a QA review', () => {
    it('shows the pick list instead of the questions for a QA task with options', async () => {
      renderPanel({}, { ...QA_TASK, pickOptions: PICK_TASK.pickOptions });

      expect(await screen.findByRole('radiogroup', { name: 'Choose one option' })).toBeVisible();
      expect(screen.queryByText('Who is the audience?')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Send answers' })).not.toBeInTheDocument();
    });

    it('shows Preparing questions while generating and the questions once ready', async () => {
      let state = 'generating';
      const calls = stubApi({
        'GET /api/v1/runs/r1/review-tasks': () => ({
          status: 200,
          body: [
            state === 'generating'
              ? { ...QA_TASK, questionsState: 'generating', questions: undefined }
              : QA_TASK,
          ],
        }),
      });
      renderRoute('/', '/', <ApprovalPanel runId="r1" />);

      expect(await screen.findByText('Preparing questions…')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Send answers' })).not.toBeInTheDocument();
      state = 'ready';

      expect(await screen.findByText('Who is the audience?', {}, { timeout: 3000 })).toBeVisible();
      expect(screen.queryByText('Preparing questions…')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Send answers' })).toBeEnabled();
      expect(calls.filter((call) => call.method === 'GET').length).toBeGreaterThan(1);
    });

    it('sends only the answered questions, under one key', async () => {
      const user = userEvent.setup();
      const calls = renderPanel(
        { [ANSWERS]: { status: 200, body: { ...QA_TASK, status: 'approved' } } },
        QA_TASK,
      );

      await user.click(await screen.findByRole('radio', { name: 'Just me' }));
      await user.click(screen.getByRole('checkbox', { name: 'Email' }));
      await user.click(screen.getByRole('checkbox', { name: 'Chat' }));
      await user.click(screen.getByRole('button', { name: 'Send answers' }));

      await waitFor(() => expect(calls.some((call) => call.path.endsWith('/answers'))).toBe(true));
      expect(calls.find((call) => call.path.endsWith('/answers'))?.body).toEqual({
        answers: { q1: 'Just me', q2: ['Email', 'Chat'] },
      });
      const sent = vi.mocked(fetch).mock.calls.find(([url]) => String(url).endsWith('/answers'));
      expect(new Headers(sent?.[1]?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('refuses the send of answers at the named question when the server answers 422', async () => {
      const user = userEvent.setup();
      renderPanel(
        {
          [ANSWERS]: refusal(422, 'Validation failed', {
            errors: { 'answers.q1': 'Choose one of the offered options' },
          }),
        },
        QA_TASK,
      );

      await user.click(await screen.findByRole('radio', { name: 'Just me' }));
      await user.click(screen.getByRole('button', { name: 'Send answers' }));

      const group = await screen.findByRole('radiogroup', { name: 'Who is the audience?' });
      await waitFor(() => expect(group).toHaveAttribute('aria-invalid', 'true'));
      expect(group).toHaveAccessibleDescription('Choose one of the offered options');
      expect(screen.getByRole('group', { name: 'Which channels?' })).not.toHaveAttribute(
        'aria-invalid',
      );
    });

    it('approves as is without an answers section and keeps the same key on a retry', async () => {
      const user = userEvent.setup();
      let reply: { status: number; body: unknown } = refusal(500, 'boom');
      const calls = renderPanel({ [APPROVE]: () => reply }, QA_TASK);

      await user.click(await screen.findByRole('button', { name: 'Approve as is' }));
      expect(await screen.findByText('Could not approve this step')).toBeInTheDocument();
      reply = { status: 200, body: { ...QA_TASK, status: 'approved' } };
      await user.click(screen.getByRole('button', { name: 'Approve as is' }));

      await waitFor(() =>
        expect(calls.filter((call) => call.path.endsWith('/approve'))).toHaveLength(2),
      );
      const keys = vi
        .mocked(fetch)
        .mock.calls.filter(([url]) => String(url).endsWith('/approve'))
        .map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
      expect(keys[0]).toBeTruthy();
      expect(keys[1]).toBe(keys[0]);
      expect(calls.find((call) => call.path.endsWith('/approve'))?.body).toBeUndefined();
    });

    it('says the review expired when a decision is refused as expired', async () => {
      const user = userEvent.setup();
      renderPanel(
        {
          [APPROVE]: refusal(409, 'The server sentence', { code: 'REVIEW_EXPIRED' }),
        },
        QA_TASK,
      );

      await user.click(await screen.findByRole('button', { name: 'Approve as is' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'This review expired, so the run stopped.',
      );
    });

    it('says the questions are not ready when the answers are refused as not ready', async () => {
      const user = userEvent.setup();
      renderPanel(
        {
          [ANSWERS]: refusal(409, 'The server sentence', { code: 'REVIEW_QUESTIONS_NOT_READY' }),
        },
        QA_TASK,
      );

      await user.click(await screen.findByRole('radio', { name: 'Just me' }));
      await user.click(screen.getByRole('button', { name: 'Send answers' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The questions are not ready yet. Try again in a moment.',
      );
    });
  });
});
