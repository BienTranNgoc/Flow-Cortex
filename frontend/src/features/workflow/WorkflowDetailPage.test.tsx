import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route } from 'react-router-dom';
import { renderRoute, settled, stubApi, type FakeReply, type Handler, type RecordedCall } from '../../test/fakeApi';
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

const SIZE_SENTENCE = 'Files must be between 1 byte and 500 MB.';
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
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
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
      expect(screen.getByText('Choose a smaller file.')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Start run' }));
      expect(sent(calls)).toEqual([]);
    });

    it('accepts an upper-case extension such as IMG_0412.MOV', async () => {
      const user = userEvent.setup();
      renderForm(CHECK);

      await user.upload(await screen.findByLabelText('Media file'), file('IMG_0412.MOV', 1_200_000));

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
      expect(screen.getByRole('progressbar', { name: 'Upload progress' })).toHaveAttribute('value', '50');

      act(() => put.respond(200));
      expect(await screen.findByRole('button', { name: 'Starting…' })).toBeInTheDocument();
      startRun({ status: 200, body: { id: 'r1', status: 'running' } });

      expect(await screen.findByText('Run page r1')).toBeInTheDocument();
      expect(sent(calls)).toEqual([
        { method: 'POST', path: '/api/v1/uploads', body: { fileName: 'holiday clip.mp4', size: 10 } },
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
      expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads', '/api/v1/workflows/w1/runs']);
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
        expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads', '/api/v1/workflows/w1/runs']),
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
      expect(sent(calls).at(-1)?.body).toEqual({ uploadId: 'up-1', originalName: 'holiday clip.mp4' });
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
        'POST /api/v1/uploads': { status: 400, body: { status: 400, errors: { fileName: TYPE_SENTENCE } } },
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
});
