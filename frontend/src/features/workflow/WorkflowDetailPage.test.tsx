import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route } from 'react-router-dom';
import {
  renderRoute,
  settled,
  stubApi,
  type FakeReply,
  type Handler,
  type RecordedCall,
} from '../../test/fakeApi';
import { FakeXhr } from '../../test/fakeXhr';
import { WorkflowDetailPage } from './WorkflowDetailPage';

const CHECK: WorkflowResponse = {
  id: 'w1',
  name: 'Check a video file',
  isShared: true,
  steps: [{ id: 'ws1', stepOrder: 1, name: 'Check the file', type: 'probe', mode: 'remote' }],
};

const ECHO: WorkflowResponse = {
  id: 'w1',
  name: 'Echo Demo',
  isShared: true,
  steps: [{ id: 'ws1', stepOrder: 1, name: 'Echo', type: 'echo', mode: 'inline' }],
};

const GRANT = {
  uploadId: 'up-1',
  url: 'http://localhost:9000/flowcortex-uploads/u/up-1?X-Amz-Signature=abc',
  method: 'PUT',
  headers: { 'Content-Type': 'video/mp4' },
  expiresAt: '2026-10-01T10:15:00Z',
  maxBytes: 524288000,
};

const INCOMPLETE: WorkflowResponse = {
  id: 'w1',
  name: 'Incomplete Demo (step 2 not configured)',
  isShared: true,
  steps: [
    { id: 'ws1', stepOrder: 1, name: 'Echo step 1', type: 'echo', mode: 'inline' },
    { id: 'ws2', stepOrder: 2, name: 'Echo step 2', type: 'echo', mode: 'inline' },
  ],
};

const INCOMPLETE_DETAIL = "This workflow can't run yet: step 2 is not fully configured.";
const STEP_2_REASON = 'Reads from step 5, which is not an earlier step';
const INCOMPLETE_REPLY: FakeReply = {
  status: 422,
  body: {
    status: 422,
    code: 'WORKFLOW_INCOMPLETE',
    detail: INCOMPLETE_DETAIL,
    errors: { step2: STEP_2_REASON },
  },
};

const SIZE_SENTENCE =
  'Files of this type must be at most 500 MB. Accepted: .mp4, .mov, .webm, .mkv.';
const AUDIO_SIZE_SENTENCE =
  'Files of this type must be at most 100 MB. Accepted: .mp3, .wav, .m4a.';
const TYPE_SENTENCE =
  "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.";

function file(name: string, size = 10) {
  const picked = new File(['0123456789'], name);
  Object.defineProperty(picked, 'size', { value: size });
  return picked;
}

function renderForm(workflow: WorkflowResponse, routes: Record<string, Handler> = {}) {
  const calls = stubApi({
    'GET /api/v1/workflows/w1': { status: 200, body: workflow },
    'GET /api/v1/capabilities': {
      status: 200,
      body: { mediaEnabled: true, productVideoEnabled: true, productVideoWorkflowId: 'pv' },
    },
    'POST /api/v1/uploads': { status: 201, body: GRANT },
    'POST /api/v1/workflows/w1/runs': { status: 200, body: { id: 'r1', status: 'running' } },
    ...routes,
  });
  const view = renderRoute(
    '/workflows/:id',
    '/workflows/w1',
    <>
      <WorkflowDetailPage />
      <Link to="/workflows">Leave</Link>
    </>,
    <>
      <Route path="/workflows/:workflowId/runs/:runId" element={<p>Run page r1</p>} />
      <Route path="/workflows" element={<p>Workflows list</p>} />
    </>,
  );
  // `calls` is the array tests care about; `queryClient` is exposed so a test can `await
  // settled(queryClient)` to wait for the workflow fetch before interacting with the Start
  // button (which stays disabled until the workflow definition lands).
  return Object.assign(calls, { queryClient: view.queryClient });
}

function sent(calls: RecordedCall[]) {
  return calls.filter((call) => call.method !== 'GET');
}

describe('WorkflowDetailPage', () => {
  beforeEach(() => {
    setToken('t', true);
    FakeXhr.instances.length = 0;
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    vi.stubGlobal('scrollTo', vi.fn());
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  describe('the steps of a workflow', () => {
    const THREE: WorkflowResponse = {
      id: 'w1',
      name: 'Three steps',
      isShared: true,
      steps: [
        { id: 'ws1', stepOrder: 1, name: 'Check the file', type: 'probe', mode: 'remote' },
        { id: 'ws2', stepOrder: 2, name: 'Pull the audio', type: 'extract_audio', mode: 'remote' },
        { stepOrder: 3, type: 'echo', mode: 'inline' },
      ],
    };

    it('are drawn as the pipeline, in order, with no status word and nothing to press', async () => {
      renderForm(THREE);

      const pipeline = await screen.findByRole('region', { name: 'Steps' });
      const items = within(pipeline).getAllByRole('listitem');
      expect(items.map((item) => item.textContent)).toEqual([
        expect.stringContaining('Check the file'),
        expect.stringContaining('Pull the audio'),
        expect.stringContaining('Step 3'),
      ]);
      expect(within(pipeline).queryByRole('button')).not.toBeInTheDocument();
      expect(pipeline.textContent).not.toMatch(/Pending|Running|Waiting|Completed|Error|Needs you/);
    });

    it('draw no pipeline for a workflow with no steps', async () => {
      renderForm({ ...ECHO, steps: [] });

      await screen.findByRole('heading', { name: 'Echo Demo' });
      expect(screen.queryByRole('region', { name: 'Steps' })).not.toBeInTheDocument();
    });
  });

  it('does not show the Product Video form while another workflow is loading', () => {
    renderForm(CHECK);

    expect(screen.getByText('Loading workflow…')).toBeInTheDocument();
    expect(
      screen.queryByRole('form', { name: 'Start a product video run' }),
    ).not.toBeInTheDocument();
  });

  it('does not show the Product Video form when another workflow fails to load', async () => {
    renderForm(CHECK, {
      'GET /api/v1/workflows/w1': { status: 500, body: { status: 500 } },
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load this workflow');
    expect(
      screen.queryByRole('form', { name: 'Start a product video run' }),
    ).not.toBeInTheDocument();
  });

  it('shows the Product Video form for the workflow the capabilities name as Product Video', async () => {
    renderForm(CHECK, {
      'GET /api/v1/capabilities': {
        status: 200,
        body: { mediaEnabled: true, productVideoEnabled: true, productVideoWorkflowId: 'w1' },
      },
    });

    expect(
      await screen.findByRole('form', { name: 'Start a product video run' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Media file')).not.toBeInTheDocument();
  });

  it('shows no start form until the capabilities say which workflow is Product Video', async () => {
    let answer: (reply: FakeReply) => void = () => {};
    const { queryClient } = renderForm(CHECK, {
      'GET /api/v1/capabilities': () => new Promise<FakeReply>((resolve) => (answer = resolve)),
    });

    await waitFor(() => expect(queryClient.isFetching()).toBe(1));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(screen.queryByLabelText('Media file')).not.toBeInTheDocument();

    answer({
      status: 200,
      body: { mediaEnabled: true, productVideoEnabled: true, productVideoWorkflowId: 'pv' },
    });
    expect(await screen.findByLabelText('Media file')).toBeInTheDocument();
  });

  it('still offers the workflow its own start form when the capabilities fail to load', async () => {
    renderForm(CHECK, {
      'GET /api/v1/capabilities': { status: 500, body: { status: 500 } },
    });

    expect(await screen.findByLabelText('Media file')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading workflow…')).not.toBeInTheDocument();
  });

  describe('a workflow whose first step is remote', () => {
    it('asks for a required "Media file" that accepts any file and never shows the step type', async () => {
      renderForm(CHECK);

      const field = await screen.findByLabelText('Media file');
      expect(field).toHaveAttribute('type', 'file');
      expect(field).toBeRequired();
      expect(field).not.toHaveAttribute('accept');
      expect(screen.getByText('Check the file')).toBeInTheDocument();
      expect(screen.getByRole('main').textContent?.toLowerCase()).not.toContain('probe');
      expect(screen.queryByLabelText('Input (optional)')).not.toBeInTheDocument();
    });

    it('asks for a file when Start is pressed without one, and sends nothing', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);
      await settled(calls.queryClient);

      const button = await screen.findByRole('button', { name: 'Start run' });
      expect(button).not.toBeDisabled();
      await user.click(button);

      expect(await screen.findByText('Choose a video or audio file to upload')).toBeInTheDocument();
      expect(sent(calls)).toEqual([]);
    });

    it('sends nothing when a file is picked', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));

      expect(sent(calls)).toEqual([]);
      expect(FakeXhr.instances).toHaveLength(0);
    });

    it('refuses a file over 500 MB on pick, and Start sends nothing', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('long.mp4', 600_000_000));

      expect(screen.getByText(SIZE_SENTENCE)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      expect(sent(calls)).toEqual([]);
    });

    it('refuses a .wav one byte over 100 MB on pick with the audio cap and formats', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('voice.wav', 104_857_601));

      expect(screen.getByText(AUDIO_SIZE_SENTENCE)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      expect(sent(calls)).toEqual([]);
    });

    it('accepts a .wav of exactly 100 MB', async () => {
      const user = userEvent.setup();
      renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('voice.wav', 104_857_600));

      expect(screen.queryByText(AUDIO_SIZE_SENTENCE)).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('names the incompletely configured step after the upload and marks it in the pipeline', async () => {
      const user = userEvent.setup();
      renderForm(
        {
          ...INCOMPLETE,
          steps: (INCOMPLETE.steps ?? []).map((step) => ({ ...step, mode: 'remote' as const })),
        },
        { 'POST /api/v1/workflows/w1/runs': INCOMPLETE_REPLY },
      );

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      act(() => FakeXhr.instances[0]!.respond(200));

      expect(await screen.findByRole('alert')).toHaveTextContent(INCOMPLETE_DETAIL);
      const [, second] = within(screen.getByRole('region', { name: 'Steps' })).getAllByRole(
        'listitem',
      );
      expect(second).toHaveTextContent('Not configured');
      expect(screen.getByRole('button', { name: 'Start run' })).toBeEnabled();
    });

    it('accepts an upper-case extension such as IMG_0412.MOV', async () => {
      const user = userEvent.setup();
      renderForm(CHECK);

      await user.upload(
        await screen.findByLabelText('Media file'),
        file('IMG_0412.MOV', 1_200_000),
      );

      expect(screen.queryByText(TYPE_SENTENCE)).not.toBeInTheDocument();
      expect(screen.queryByText(SIZE_SENTENCE)).not.toBeInTheDocument();
    });

    it('lets an .avi be picked and explains the type on pick, and Start sends nothing', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('holiday.avi'));

      expect(screen.getByText(TYPE_SENTENCE)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      expect(sent(calls)).toEqual([]);
    });

    it('uploads with a moving bar, then starts the run and opens the run page', async () => {
      const user = userEvent.setup();
      let startRun!: (reply: FakeReply) => void;
      const started = new Promise<FakeReply>((resolve) => (startRun = resolve));
      const calls = renderForm(CHECK, { 'POST /api/v1/workflows/w1/runs': () => started });

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));

      const busy = await screen.findByRole('button', { name: 'Uploading… 0 %' });
      expect(busy).toHaveAttribute('aria-busy', 'true');
      expect(busy).toBeEnabled();
      busy.focus();
      expect(busy).toHaveFocus();

      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      const put = FakeXhr.instances[0]!;
      expect(put.method).toBe('PUT');
      expect(put.url).toBe(GRANT.url);
      expect(put.headers).toEqual({ 'Content-Type': 'video/mp4' });

      act(() => put.progress(5, 10));
      expect(screen.getByRole('button', { name: 'Uploading… 50 %' })).toBeInTheDocument();
      expect(screen.getByRole('progressbar', { name: 'Upload progress' })).toHaveAttribute(
        'value',
        '50',
      );

      act(() => put.respond(200));
      expect(await screen.findByRole('button', { name: 'Starting…' })).toBeInTheDocument();
      startRun({ status: 200, body: { id: 'r1', status: 'running' } });

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls)).toEqual([
        {
          method: 'POST',
          path: '/api/v1/uploads',
          body: { fileName: 'holiday clip.mp4', size: 10 },
        },
        {
          method: 'POST',
          path: '/api/v1/workflows/w1/runs',
          body: { uploadId: 'up-1', originalName: 'holiday clip.mp4' },
        },
      ]);
    });

    it('counts two quick presses of Start as one: one /uploads, one PUT, one run start after it', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      const start = screen.getByRole('button', { name: 'Start run' });
      await user.click(start);
      await user.click(start);
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      await user.click(screen.getByRole('button', { name: /Uploading/ }));
      expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads']);

      act(() => FakeXhr.instances[0]!.respond(200));

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls).map((call) => call.path)).toEqual([
        '/api/v1/uploads',
        '/api/v1/workflows/w1/runs',
      ]);
      expect(FakeXhr.instances).toHaveLength(1);
      expect(screen.queryByText('Upload the file again')).not.toBeInTheDocument();
    });

    it('still starts the run after the member left mid-upload, but does not move them to it', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      await user.click(screen.getByRole('link', { name: 'Leave' }));
      expect(await screen.findByText('Workflows list')).toBeInTheDocument();

      act(() => FakeXhr.instances[0]!.respond(200));

      await waitFor(() =>
        expect(sent(calls).map((call) => call.path)).toEqual([
          '/api/v1/uploads',
          '/api/v1/workflows/w1/runs',
        ]),
      );
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(screen.getByText('Workflows list')).toBeInTheDocument();
      expect(screen.queryByText('Run page r1')).not.toBeInTheDocument();
    });

    it('ignores a new pick while an upload is running and keeps uploading the first file', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);
      const field = await screen.findByLabelText('Media file');

      await user.upload(field, file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      await user.upload(field, file('holiday.avi'));

      expect(screen.queryByText(TYPE_SENTENCE)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Uploading/ })).toBeInTheDocument();
      act(() => FakeXhr.instances[0]!.respond(200));
      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls).at(-1)?.body).toEqual({
        uploadId: 'up-1',
        originalName: 'holiday clip.mp4',
      });
    });

    it('says the upload did not finish and Retry starts again from /uploads', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      act(() => FakeXhr.instances[0]!.failNetwork());

      expect(await screen.findByText("The upload didn't finish. Try again.")).toBeInTheDocument();
      expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads']);

      await user.click(screen.getByRole('button', { name: 'Retry' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
      act(() => FakeXhr.instances[1]!.respond(200));

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls).map((call) => call.path)).toEqual([
        '/api/v1/uploads',
        '/api/v1/uploads',
        '/api/v1/workflows/w1/runs',
      ]);
    });

    it('shows a 400 from /uploads on the form', async () => {
      const user = userEvent.setup();
      renderForm(CHECK, {
        'POST /api/v1/uploads': {
          status: 400,
          body: { status: 400, errors: { fileName: TYPE_SENTENCE } },
        },
      });

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));

      expect(await screen.findByText(TYPE_SENTENCE)).toBeInTheDocument();
      expect(FakeXhr.instances).toHaveLength(0);
    });

    it('shows a 400 from the run start on the form, and Start uploads the file afresh', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK, {
        'POST /api/v1/workflows/w1/runs': {
          status: 400,
          body: { status: 400, errors: { uploadId: 'Upload the file again' } },
        },
      });

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      act(() => FakeXhr.instances[0]!.respond(200));

      expect(await screen.findByText('Upload the file again')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
      expect(sent(calls).filter((call) => call.path === '/api/v1/uploads')).toHaveLength(2);
    });

    it('names the switched-off media engine from the 503 detail, with the next action', async () => {
      const user = userEvent.setup();
      renderForm(CHECK, {
        'POST /api/v1/uploads': {
          status: 503,
          body: { status: 503, detail: 'Media engine is currently disabled' },
        },
      });

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Media engine is currently disabled');
      expect(alert).toHaveTextContent('Try again later, or go back to the Workflows list.');
      expect(alert).not.toHaveTextContent('Workflow execution is currently disabled');
    });
  });

  describe('a workflow whose first step is inline', () => {
    it('keeps the optional text input', async () => {
      renderForm(ECHO);

      expect(await screen.findByLabelText('Input (optional)')).toBeInTheDocument();
      expect(screen.queryByLabelText('Media file')).not.toBeInTheDocument();
      expect(screen.getByRole('main').textContent).not.toContain('(echo)');
    });

    it('shows the 503 detail when present, with the next action', async () => {
      const user = userEvent.setup();
      const calls = renderForm(ECHO, {
        'POST /api/v1/workflows/w1/runs': {
          status: 503,
          body: { status: 503, detail: 'Runs are paused for maintenance' },
        },
      });
      await settled(calls.queryClient);

      const button = await screen.findByRole('button', { name: 'Start run' });
      expect(button).not.toBeDisabled();
      await user.click(button);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Runs are paused for maintenance');
      expect(alert).toHaveTextContent('Try again later, or go back to the Workflows list.');
    });
  });

  describe('a workflow with an incompletely configured step', () => {
    it('starting "Incomplete Demo" says which step is not configured, lists why, and marks step 2', async () => {
      const user = userEvent.setup();
      const calls = renderForm(INCOMPLETE, { 'POST /api/v1/workflows/w1/runs': INCOMPLETE_REPLY });
      await settled(calls.queryClient);
      const pipeline = screen.getByRole('region', { name: 'Steps' });
      expect(within(pipeline).queryByText('Not configured')).not.toBeInTheDocument();

      await user.click(await screen.findByRole('button', { name: 'Start run' }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(INCOMPLETE_DETAIL);
      expect(within(alert).getByRole('listitem')).toHaveTextContent(
        `Step 2 · Echo step 2: ${STEP_2_REASON}`,
      );
      const [first, second] = within(pipeline).getAllByRole('listitem');
      expect(second).toHaveTextContent('Not configured');
      expect(first).not.toHaveTextContent('Not configured');
      expect(screen.queryByText('Run page r1')).not.toBeInTheDocument();
    });

    it('drops the refusal and the mark when the next start fails for another reason', async () => {
      const user = userEvent.setup();
      let reply: FakeReply = INCOMPLETE_REPLY;
      const calls = renderForm(INCOMPLETE, { 'POST /api/v1/workflows/w1/runs': () => reply });
      await settled(calls.queryClient);

      await user.click(await screen.findByRole('button', { name: 'Start run' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(INCOMPLETE_DETAIL);

      reply = { status: 503, body: { status: 503, detail: 'Runs are paused for maintenance' } };
      await user.click(screen.getByRole('button', { name: 'Start run' }));

      expect(await screen.findByText('Runs are paused for maintenance')).toBeInTheDocument();
      expect(screen.getAllByRole('alert')).toHaveLength(1);
      expect(screen.queryByText(INCOMPLETE_DETAIL)).not.toBeInTheDocument();
      expect(
        within(screen.getByRole('region', { name: 'Steps' })).queryByText('Not configured'),
      ).not.toBeInTheDocument();
    });
  });

  describe('credits', () => {
    const SHORT_SENTENCE = 'Not enough credits: you have 30, this run needs 60 (short 30).';

    function notEnough(balance: number): FakeReply {
      return {
        status: 402,
        body: {
          status: 402,
          code: 'INSUFFICIENT_CREDITS',
          detail: 'Not enough credits',
          balance,
          required: 60,
          shortfall: 60 - balance,
        },
      };
    }

    /** The first start is refused with `first`; every later one starts run r1. */
    function refusedOnce(first: FakeReply): () => FakeReply {
      let count = 0;
      return () => {
        count += 1;
        return count === 1 ? first : { status: 200, body: { id: 'r1', status: 'running' } };
      };
    }

    it('shows the estimate next to the start button', async () => {
      renderForm({ ...ECHO, estimatedCredits: 60 });

      const form = await screen.findByRole('form', { name: 'Start run' });
      expect(form.parentElement).toHaveTextContent('Estimated cost: 60 credits');
    });

    it('shows no estimate when the workflow carries none', async () => {
      const calls = renderForm(ECHO);
      await settled(calls.queryClient);

      expect(await screen.findByRole('button', { name: 'Start run' })).toBeInTheDocument();
      expect(screen.queryByText(/Estimated cost/)).not.toBeInTheDocument();
    });

    it('says the run needs more credits, and "Run anyway" resends the start with the acknowledgement', async () => {
      const user = userEvent.setup();
      const calls = renderForm(ECHO, {
        'POST /api/v1/workflows/w1/runs': refusedOnce(notEnough(30)),
      });
      await settled(calls.queryClient);

      await user.click(await screen.findByRole('button', { name: 'Start run' }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(SHORT_SENTENCE);
      await user.click(within(alert).getByRole('button', { name: 'Run anyway' }));

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls).map((call) => call.body)).toEqual([{}, { acknowledgeCost: true }]);
    });

    it('offers no "Run anyway" when the balance is 0', async () => {
      const user = userEvent.setup();
      const calls = renderForm(ECHO, { 'POST /api/v1/workflows/w1/runs': notEnough(0) });
      await settled(calls.queryClient);

      await user.click(await screen.findByRole('button', { name: 'Start run' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Not enough credits: you have 0, this run needs 60 (short 60).',
      );
      expect(screen.queryByRole('button', { name: 'Run anyway' })).not.toBeInTheDocument();
    });

    it('"Run anyway" on the media form starts with the same upload, without uploading again', async () => {
      const user = userEvent.setup();
      const calls = renderForm(CHECK, {
        'POST /api/v1/workflows/w1/runs': refusedOnce(notEnough(30)),
      });

      await user.upload(await screen.findByLabelText('Media file'), file('holiday clip.mp4'));
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
      act(() => FakeXhr.instances[0]!.respond(200));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(SHORT_SENTENCE);
      await user.click(within(alert).getByRole('button', { name: 'Run anyway' }));

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(FakeXhr.instances).toHaveLength(1);
      expect(sent(calls)).toEqual([
        {
          method: 'POST',
          path: '/api/v1/uploads',
          body: { fileName: 'holiday clip.mp4', size: 10 },
        },
        {
          method: 'POST',
          path: '/api/v1/workflows/w1/runs',
          body: { uploadId: 'up-1', originalName: 'holiday clip.mp4' },
        },
        {
          method: 'POST',
          path: '/api/v1/workflows/w1/runs',
          body: { uploadId: 'up-1', originalName: 'holiday clip.mp4', acknowledgeCost: true },
        },
      ]);
    });

    it('marks the balance stale once a run starts', async () => {
      const user = userEvent.setup();
      const calls = renderForm(ECHO);
      await settled(calls.queryClient);
      calls.queryClient.setQueryData(['credits', 'balance'], { balance: 1000 });

      await user.click(await screen.findByRole('button', { name: 'Start run' }));

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(calls.queryClient.getQueryState(['credits', 'balance'])?.isInvalidated).toBe(true);
    });
  });
});
