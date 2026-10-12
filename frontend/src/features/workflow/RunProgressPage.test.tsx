import { setToken, type RunResponse, type RunStepResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { onTestFinished } from 'vitest';
import { renderRoute, settled, stubApi, type FakeReply, type Handler } from '../../test/fakeApi';
import { openInCurrentTab } from './browser';
import { RunProgressPage } from './RunProgressPage';

vi.mock('./browser', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openInCurrentTab: vi.fn(),
}));

const ENGINE_WORDS = ['lease', 'executor', 'heartbeat', 'dispatch', 'capability'];
const MEMBER_PATH_WORDS = ['probe', 'engine', 'capability'];
const PROBE_OUTPUT =
  '{"container":"mov,mp4,m4a,3gp,3g2,mj2","durationMs":1000,"width":320,"height":240,"videoCodec":"h264","audioCodec":"aac"}';
const DETAILS = { name: 'video-details.json', mime: 'application/json', bytes: 4200 };
const AUDIO = { name: 'source-audio.wav', mime: 'audio/wav', bytes: 32044 };
const URL_PATH = '/api/v1/runs/r1/steps/1/outputs/video-details.json/url';
const AUDIO_URL_PATH = '/api/v1/runs/r1/steps/2/outputs/source-audio.wav/url';

function step(overrides: Partial<RunStepResponse> = {}): RunStepResponse {
  return {
    id: 's1',
    stepOrder: 1,
    type: 'probe',
    mode: 'remote',
    status: 'running',
    attempt: 1,
    deliveryCount: 0,
    outputs: [],
    ...overrides,
  };
}

function run(steps: RunStepResponse[], overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: 'r1',
    workflowId: 'w1',
    status: 'running',
    input: 'holiday clip.mp4',
    steps,
    ...overrides,
  };
}

function completedCheck(output = PROBE_OUTPUT): RunResponse {
  return run(
    [
      step({
        status: 'completed',
        pickedUpAt: '2026-10-01T10:00:01Z',
        progressPercent: 100,
        output,
        outputs: [DETAILS],
      }),
    ],
    { status: 'completed' },
  );
}

function completedPipeline(): RunResponse {
  return run(
    [
      step({
        status: 'completed',
        pickedUpAt: '2026-10-01T10:00:01Z',
        progressPercent: 100,
        output: PROBE_OUTPUT,
        outputs: [DETAILS],
      }),
      step({
        id: 's2',
        stepOrder: 2,
        type: 'extract_audio',
        status: 'completed',
        pickedUpAt: '2026-10-01T10:00:02Z',
        progressPercent: 100,
        output: '{"sampleRate":16000,"channels":1,"codec":"pcm_s16le","timelineOriginMs":0}',
        outputs: [AUDIO],
      }),
    ],
    { status: 'completed' },
  );
}

/** Only the run is served: the page must not need the workflow to show it. */
function renderRun(body: RunResponse, extra: Record<string, Handler> = {}) {
  const calls = stubApi({
    'GET /api/v1/runs/r1': { status: 200, body },
    ...extra,
  });
  const { queryClient } = renderRoute(
    '/workflows/:workflowId/runs/:runId',
    '/workflows/w1/runs/r1',
    <RunProgressPage />,
  );
  return { calls, queryClient };
}

/** The page's main text outside the small-print error code, which is shown verbatim for support. */
function memberText(): string {
  const main = screen.getByRole('main').cloneNode(true) as HTMLElement;
  main.querySelectorAll('.run-step-code').forEach((code) => code.remove());
  return main.textContent ?? '';
}

/** jsdom's Blob has no `text()`. */
function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.readAsText(blob);
  });
}

function occurrences(text: string, part: string): number {
  return text.split(part).length - 1;
}

describe('RunProgressPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps the loading state while the run loads', () => {
    stubApi({ 'GET /api/v1/runs/r1': () => new Promise<FakeReply>(() => {}) });
    renderRoute('/workflows/:workflowId/runs/:runId', '/workflows/w1/runs/r1', <RunProgressPage />);

    expect(screen.getByText('Loading run…')).toBeInTheDocument();
  });

  it('names the checked file as text', async () => {
    renderRun(run([step()], { input: '<img src=x onerror=alert(1)>holiday clip.mp4' }));

    expect(
      await screen.findByText('File: <img src=x onerror=alert(1)>holiday clip.mp4'),
    ).toBeInTheDocument();
    expect(screen.getByRole('main').querySelector('img')).toBeNull();
  });

  it('shows "File: holiday clip.mp4" for a run whose first step is remote', async () => {
    renderRun(run([step()]));

    expect(await screen.findByText('File: holiday clip.mp4')).toBeInTheDocument();
  });

  it("takes each step's mode from the run itself and never loads the workflow, which may have changed since", async () => {
    const { calls, queryClient } = renderRun(
      run(
        [
          step({
            status: 'error',
            errorCode: 'queue_timeout',
            errorMessage: 'Not picked up in time',
          }),
        ],
        {
          status: 'error',
        },
      ),
    );

    expect(await screen.findByText('File: holiday clip.mp4')).toBeInTheDocument();
    expect(
      screen.getByText('The media engine was too busy to start this in time. Try running again.'),
    ).toBeInTheDocument();
    await settled(queryClient);
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/runs/r1']);
  });

  it('shows no file line for a run whose first step is inline', async () => {
    const { queryClient } = renderRun(
      run([step({ type: 'echo', mode: 'inline' })], { input: 'hello' }),
    );

    await screen.findByText('Step 1');
    await settled(queryClient);
    expect(screen.queryByText(/^File:/)).not.toBeInTheDocument();
  });

  it('shows "Waiting to start…" for a running step that was not picked up yet', async () => {
    renderRun(run([step()]));

    expect(await screen.findByText('Waiting to start…')).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('labels a running step that was not picked up yet "Waiting", and "Running" once it was', async () => {
    renderRun(
      run([
        step(),
        step({ id: 's2', stepOrder: 2, pickedUpAt: '2026-10-01T10:00:01Z', progressPercent: 10 }),
      ]),
    );

    await screen.findByText('Waiting to start…');
    const pills = [...screen.getByRole('main').querySelectorAll('.run-step-status')].map(
      (pill) => pill.textContent,
    );
    expect(pills).toEqual(['Waiting', 'Running']);
  });

  it('shows the progress once the step was picked up', async () => {
    renderRun(run([step({ pickedUpAt: '2026-10-01T10:00:01Z', progressPercent: 40 })]));

    const bar = await screen.findByRole('progressbar');
    expect(bar).toHaveAttribute('value', '40');
    expect(screen.getByText('40 %')).toBeInTheDocument();
    expect(screen.queryByText('Waiting to start…')).not.toBeInTheDocument();
  });

  it('notes a redelivered job as "Retried N×" and says nothing for a first delivery', async () => {
    renderRun(
      run([
        step({ pickedUpAt: '2026-10-01T10:00:01Z', deliveryCount: 2 }),
        step({
          id: 's2',
          stepOrder: 2,
          type: 'echo',
          mode: 'inline',
          status: 'pending',
          deliveryCount: 0,
        }),
      ]),
    );

    expect(await screen.findByText('Retried 2×')).toBeInTheDocument();
    expect(screen.getAllByText(/Retried/)).toHaveLength(1);
  });

  const engineMessage = 'Lease expired: executor heartbeat lost after dispatch (capability probe)';

  describe.each<[string | null, string | null, string]>([
    ['input_rejected', engineMessage, "We couldn't use that uploaded file. Upload it again."],
    [
      'probe_failed',
      engineMessage,
      "We couldn't read that file as audio or video. Upload a video or audio file (for example .mp4).",
    ],
    ['lease_expired', engineMessage, 'The media engine stopped responding. Try running again.'],
    [
      'queue_timeout',
      engineMessage,
      'The media engine was too busy to start this in time. Try running again.',
    ],
    [
      'run_timeout',
      engineMessage,
      'This file took too long to process and was stopped. Try a shorter file.',
    ],
    ['media_disabled', engineMessage, 'The media engine is switched off right now.'],
    ['contract_violation', engineMessage, 'The media engine rejected this job. Try again later.'],
    [null, 'Unsupported step type: resize', 'Unsupported step type: resize'],
    ['thumbnail_failed', 'The thumbnail could not be made.', 'The thumbnail could not be made.'],
    [null, null, 'This step stopped with an error.'],
  ])('a remote step that ended in error with code %s and message %s', (code, message, sentence) => {
    beforeEach(() => {
      renderRun(
        run(
          [
            step({
              status: 'error',
              pickedUpAt: '2026-10-01T10:00:01Z',
              ...(code === null ? {} : { errorCode: code }),
              ...(message === null ? {} : { errorMessage: message }),
            }),
          ],
          { status: 'error', errorMessage: engineMessage },
        ),
      );
    });

    it('shows the member sentence once, never the engine message', async () => {
      await screen.findByText(sentence);
      const text = memberText();

      expect(occurrences(text, sentence)).toBe(1);
      expect(text).not.toContain(engineMessage);
      for (const word of ENGINE_WORDS) {
        expect(text.toLowerCase()).not.toContain(word);
      }
    });

    it('shows the code only as small print, and the way back to the workflow', async () => {
      await screen.findByText(sentence);
      const codes = screen.getByRole('main').querySelectorAll('.run-step-code');

      if (code === null) {
        expect(codes).toHaveLength(0);
      } else {
        expect([...codes].map((element) => [element.tagName, element.textContent])).toEqual([
          ['SMALL', code],
        ]);
      }
      expect(screen.getByRole('link', { name: 'Back to workflow' })).toHaveAttribute(
        'href',
        '/workflows/w1',
      );
    });
  });

  it.each(['queue_timeout', 'lease_expired', 'run_timeout'])(
    'says an inline step closed with %s stopped before it finished, without naming the media engine',
    async (code) => {
      renderRun(
        run(
          [
            step({
              type: 'echo',
              mode: 'inline',
              status: 'error',
              errorCode: code,
              errorMessage: engineMessage,
            }),
          ],
          { status: 'error', input: 'hello' },
        ),
      );

      expect(
        await screen.findByText('This step stopped before it finished. Try running again.'),
      ).toBeInTheDocument();
      expect(memberText().toLowerCase()).not.toContain('media engine');
      expect(screen.getByRole('main').querySelector('.run-step-code')?.textContent).toBe(code);
    },
  );

  it('explains a run that failed with no failing step', async () => {
    renderRun(
      run([step({ status: 'completed', pickedUpAt: '2026-10-01T10:00:01Z', output: 'hello' })], {
        status: 'error',
        errorMessage: engineMessage,
      }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('This step stopped with an error.');
    expect(memberText()).not.toContain(engineMessage);
  });

  it('renders a structured output as labelled fields with the technical ones hidden', async () => {
    renderRun(completedCheck());

    await screen.findByText('Duration');
    const fields = screen.getByRole('main').querySelector('dl');
    expect(fields).not.toBeNull();
    const pairs = [...fields!.querySelectorAll('dt')].map((term) => [
      term.textContent,
      term.nextElementSibling?.textContent,
    ]);
    expect(pairs).toEqual([
      ['Duration', '1.0 s'],
      ['Dimensions', '320 × 240'],
      ['Video codec', 'h264'],
      ['Audio codec', 'aac'],
    ]);
    expect(screen.getByRole('main').textContent).not.toContain('{');
    expect(screen.getByRole('main').textContent).not.toContain('mov,mp4');
  });

  it('reveals the full JSON, container included, under "Show raw"', async () => {
    const user = userEvent.setup();
    renderRun(completedCheck());

    await user.click(await screen.findByRole('button', { name: 'Show raw' }));

    expect(screen.getByRole('main').textContent).toContain(
      '"container": "mov,mp4,m4a,3gp,3g2,mj2"',
    );
  });

  it('leaves out absent fields of an audio-only file', async () => {
    renderRun(
      completedCheck(
        '{"container":"mov,mp4,m4a","durationMs":61234,"width":null,"height":null,"videoCodec":null,"audioCodec":"aac"}',
      ),
    );

    await screen.findByText('Audio codec');
    const terms = [...screen.getByRole('main').querySelectorAll('dt')].map(
      (term) => term.textContent,
    );
    expect(terms).toEqual(['Duration', 'Audio codec']);
    expect(screen.getByText('61.2 s')).toBeInTheDocument();
    expect(screen.getByRole('main').textContent).not.toContain('null');
  });

  it('leaves out a labelled field whose value is not a plain value', async () => {
    renderRun(
      completedCheck('{"durationMs":1000,"videoCodec":{"name":"h264"},"audioCodec":"aac"}'),
    );

    await screen.findByText('Audio codec');
    const terms = [...screen.getByRole('main').querySelectorAll('dt')].map(
      (term) => term.textContent,
    );
    expect(terms).toEqual(['Duration', 'Audio codec']);
    expect(screen.getByRole('main').textContent).not.toContain('[object Object]');
  });

  it('shows a plain output as text', async () => {
    renderRun(
      run(
        [
          step({
            type: 'echo',
            mode: 'inline',
            status: 'completed',
            pickedUpAt: '2026-10-01T10:00:01Z',
            output: 'hello',
          }),
        ],
        { status: 'completed', input: 'hello' },
      ),
    );

    expect(await screen.findByText('hello')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show raw' })).not.toBeInTheDocument();
    expect(screen.getByRole('main').querySelector('dl')).toBeNull();
  });

  it('offers the summary first, then the technical details', async () => {
    renderRun(completedCheck());

    await screen.findByRole('button', { name: 'Download summary (.txt)' });
    const names = within(screen.getByRole('main'))
      .getAllByRole('button')
      .map((button) => button.textContent)
      .filter((name) => name?.startsWith('Download'));
    expect(names).toEqual(['Download summary (.txt)', 'Download technical details (JSON file)']);
  });

  it('saves video-summary.txt built from the same labelled fields, in the current tab', async () => {
    const user = userEvent.setup();
    const blobs: Blob[] = [];
    // jsdom implements neither, so they are defined for this test only, then removed again.
    Object.assign(URL, {
      createObjectURL: (blob: Blob) => {
        blobs.push(blob);
        return 'blob:summary';
      },
      revokeObjectURL: () => {},
    });
    onTestFinished(() => {
      Reflect.deleteProperty(URL, 'createObjectURL');
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    });
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this);
    });
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    renderRun(completedCheck());

    await user.click(await screen.findByRole('button', { name: 'Download summary (.txt)' }));

    expect(clicked).toHaveLength(1);
    expect(clicked[0]!.getAttribute('download')).toBe('video-summary.txt');
    expect(clicked[0]!.getAttribute('href')).toBe('blob:summary');
    expect(clicked[0]!.target).toBe('');
    expect(open).not.toHaveBeenCalled();
    expect(blobs[0]!.type).toBe('text/plain;charset=utf-8');
    expect(await readBlob(blobs[0]!)).toBe(
      'File: holiday clip.mp4\nDuration: 1.0 s\nDimensions: 320 × 240\nVideo codec: h264\nAudio codec: aac\n',
    );
  });

  it('requests the technical details URL on demand and opens it in the current tab', async () => {
    const user = userEvent.setup();
    let answer!: (reply: FakeReply) => void;
    const reply = new Promise<FakeReply>((resolve) => (answer = resolve));
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const { calls } = renderRun(completedCheck(), { [`GET ${URL_PATH}`]: () => reply });

    await user.click(
      await screen.findByRole('button', { name: 'Download technical details (JSON file)' }),
    );

    const busy = await screen.findByRole('button', { name: 'Getting the file…' });
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).toBeEnabled();
    busy.focus();
    expect(busy).toHaveFocus();
    await user.click(busy);
    answer({
      status: 200,
      body: {
        url: 'http://localhost:9000/flowcortex-media/x?sig=1',
        expiresAt: '2026-10-01T10:15:00Z',
      },
    });

    await waitFor(() =>
      expect(openInCurrentTab).toHaveBeenCalledWith(
        'http://localhost:9000/flowcortex-media/x?sig=1',
      ),
    );
    expect(calls.filter((call) => call.path === URL_PATH)).toHaveLength(1);
    expect(open).not.toHaveBeenCalled();
  });

  it('says "Could not get download link" inline and re-enables the action when the request fails', async () => {
    const user = userEvent.setup();
    renderRun(completedCheck(), { [`GET ${URL_PATH}`]: { status: 404, body: { status: 404 } } });

    await user.click(
      await screen.findByRole('button', { name: 'Download technical details (JSON file)' }),
    );

    expect(await screen.findByText('Could not get download link')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Download technical details (JSON file)' }),
    ).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('renders metadata and WAV download button for a completed extract_audio step', async () => {
    renderRun(completedPipeline());

    await screen.findByText('Sample rate');
    expect(screen.getByText('16000')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('pcm_s16le')).toBeInTheDocument();

    expect(
      screen.getByRole('button', { name: 'Download extracted audio (WAV)' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download summary (.txt)' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Download technical details (JSON file)' }),
    ).toBeInTheDocument();
  });

  it('requests the extracted audio WAV URL on demand and opens it in the current tab', async () => {
    const user = userEvent.setup();
    let answer!: (reply: FakeReply) => void;
    const reply = new Promise<FakeReply>((resolve) => (answer = resolve));
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    const { calls } = renderRun(completedPipeline(), { [`GET ${AUDIO_URL_PATH}`]: () => reply });

    await user.click(await screen.findByRole('button', { name: 'Download extracted audio (WAV)' }));

    const busy = await screen.findByRole('button', { name: 'Getting the file…' });
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).toBeEnabled();
    busy.focus();
    expect(busy).toHaveFocus();
    await user.click(busy);
    answer({
      status: 200,
      body: {
        url: 'http://localhost:9000/flowcortex-media/wav?sig=2',
        expiresAt: '2026-10-01T10:15:00Z',
      },
    });

    await waitFor(() =>
      expect(openInCurrentTab).toHaveBeenCalledWith(
        'http://localhost:9000/flowcortex-media/wav?sig=2',
      ),
    );
    expect(calls.filter((call) => call.path === AUDIO_URL_PATH)).toHaveLength(1);
    expect(open).not.toHaveBeenCalled();
  });

  it('says "Could not get download link" inline and re-enables the WAV download action when the request fails', async () => {
    const user = userEvent.setup();
    renderRun(completedPipeline(), {
      [`GET ${AUDIO_URL_PATH}`]: { status: 404, body: { status: 404 } },
    });

    await user.click(await screen.findByRole('button', { name: 'Download extracted audio (WAV)' }));

    expect(await screen.findByText('Could not get download link')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Download extracted audio (WAV)' }),
    ).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('uses no probe, engine or capability word in a label, link or file name', async () => {
    renderRun(completedCheck());

    await screen.findByRole('button', { name: 'Download summary (.txt)' });
    const main = screen.getByRole('main');
    const labels = [
      ...[...main.querySelectorAll('button, a, dt, label, h1, h2')].map(
        (element) => element.textContent ?? '',
      ),
      'video-summary.txt',
      DETAILS.name,
    ];
    for (const label of labels) {
      for (const word of MEMBER_PATH_WORDS) {
        expect(label.toLowerCase()).not.toContain(word);
      }
    }
  });

  describe('a run waiting for approval', () => {
    const TASKS_PATH = '/api/v1/runs/r1/review-tasks';
    const APPROVE = 'POST /api/v1/review-tasks/t1/approve';
    const REJECT = 'POST /api/v1/review-tasks/t1/reject';
    const TASK = {
      id: 't1',
      runId: 'r1',
      runStepId: 's2',
      reviewType: 'APPROVAL_CHECKPOINT',
      status: 'pending',
      originalPayload: 'Draft caption',
    };

    function waiting(): RunResponse {
      return run(
        [
          step({ type: 'echo', mode: 'inline', status: 'completed', output: 'Draft caption' }),
          step({
            id: 's2',
            stepOrder: 2,
            type: 'APPROVAL_CHECKPOINT',
            mode: 'inline',
            status: 'pending',
          }),
        ],
        { status: 'waiting_approval', input: 'Draft caption' },
      );
    }

    /** Serves the paused run until a decision succeeds, then the run as it continues. */
    function renderWaiting(decisions: Record<string, Handler> = {}) {
      let decided = false;
      const decide = (handler: Handler) => async () => {
        const reply = typeof handler === 'function' ? await handler() : handler;
        decided = reply.status < 400;
        return reply;
      };
      const calls = stubApi({
        'GET /api/v1/runs/r1': () => ({
          status: 200,
          body: decided ? { ...waiting(), status: 'running' } : waiting(),
        }),
        [`GET ${TASKS_PATH}`]: {
          status: 200,
          body: [{ ...TASK, status: 'approved', id: 't0' }, TASK],
        },
        ...Object.fromEntries(
          Object.entries(decisions).map(([route, handler]) => [route, decide(handler)]),
        ),
      });
      renderRoute(
        '/workflows/:workflowId/runs/:runId',
        '/workflows/w1/runs/r1',
        <RunProgressPage />,
      );
      return calls;
    }

    it('says it is waiting, keeps polling, and shows the pending content with Approve and Reject', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      onTestFinished(() => {
        vi.useRealTimers();
      });
      const calls = renderWaiting();

      expect(
        await screen.findByRole('heading', { name: 'Waiting for your approval' }),
      ).toBeInTheDocument();
      expect(await screen.findByLabelText('Content to approve')).toHaveValue('Draft caption');
      expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(2500);
      expect(calls.filter((call) => call.path === '/api/v1/runs/r1').length).toBeGreaterThan(1);
    });

    it('approves the edited content and moves on with the run', async () => {
      const user = userEvent.setup();
      const calls = renderWaiting({
        [APPROVE]: { status: 200, body: { ...TASK, status: 'approved' } },
      });

      const content = await screen.findByLabelText('Content to approve');
      await user.clear(content);
      await user.type(content, 'Final caption');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      await waitFor(() =>
        expect(calls).toContainEqual({
          method: 'POST',
          path: '/api/v1/review-tasks/t1/approve',
          body: { reviewedPayload: 'Final caption' },
        }),
      );
      expect(await screen.findByRole('heading', { name: 'Run in progress' })).toBeInTheDocument();
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
    });

    it('approves without a body when the content was not changed', async () => {
      const user = userEvent.setup();
      const calls = renderWaiting({
        [APPROVE]: { status: 200, body: { ...TASK, status: 'approved' } },
      });

      await screen.findByLabelText('Content to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      await waitFor(() =>
        expect(calls).toContainEqual({
          method: 'POST',
          path: '/api/v1/review-tasks/t1/approve',
          body: undefined,
        }),
      );
    });

    it('rejects the pending content', async () => {
      const user = userEvent.setup();
      const calls = renderWaiting({
        [REJECT]: { status: 200, body: { ...TASK, status: 'rejected' } },
      });

      await screen.findByLabelText('Content to approve');
      await user.click(screen.getByRole('button', { name: 'Reject' }));

      await waitFor(() =>
        expect(calls).toContainEqual({
          method: 'POST',
          path: '/api/v1/review-tasks/t1/reject',
          body: undefined,
        }),
      );
      expect(calls.some((call) => call.path.endsWith('/approve'))).toBe(false);
    });

    it('says the approval failed when the task was already decided', async () => {
      const user = userEvent.setup();
      renderWaiting({
        [APPROVE]: { status: 409, body: { status: 409, detail: 'Review task is not pending' } },
      });

      await screen.findByLabelText('Content to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not approve this step');
      expect(screen.getByRole('button', { name: 'Approve' })).toHaveAttribute('aria-busy', 'false');
    });

    it('shows no review for a finished run', async () => {
      const { calls, queryClient } = renderRun(completedCheck());

      await screen.findByRole('heading', { name: 'Run completed' });
      await settled(queryClient);
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
      expect(calls.some((call) => call.path === TASKS_PATH)).toBe(false);
    });
  });

  describe('a run saved earlier', () => {
    it('keeps the queue_timeout sentence after the cause is gone and stops polling a finished run', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      onTestFinished(() => {
        vi.useRealTimers();
      });
      const { calls } = renderRun(
        run(
          [
            step({
              status: 'error',
              errorCode: 'queue_timeout',
              errorMessage: 'Not picked up in time',
            }),
          ],
          {
            status: 'error',
          },
        ),
      );

      expect(
        await screen.findByText(
          'The media engine was too busy to start this in time. Try running again.',
        ),
      ).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(5000);
      expect(calls.filter((call) => call.path === '/api/v1/runs/r1')).toHaveLength(1);
    });

    it('still offers the downloads of a finished run and no start or re-upload action', async () => {
      renderRun(completedCheck());

      await screen.findByRole('button', { name: 'Download technical details (JSON file)' });
      const actions = [...screen.getByRole('main').querySelectorAll('button, a')].map(
        (element) => element.textContent ?? '',
      );
      expect(actions.filter((name) => /start|upload/i.test(name))).toEqual([]);
    });

    it('renders a run written before pickup, retries and outputs were recorded', async () => {
      const older = {
        id: 's1',
        stepOrder: 1,
        type: 'probe',
        status: 'completed',
        output: '{"durationMs":1000}',
        attempt: 1,
      } as unknown as RunStepResponse;
      const { queryClient } = renderRun(
        run([older], {
          status: 'completed',
          input: '{"key":"source","bucket":"flowcortex-uploads","objectKey":"owner/upload"}',
        }),
      );

      expect(await screen.findByText('1.0 s')).toBeInTheDocument();
      await settled(queryClient);
      expect(screen.queryByText(/Retried/)).not.toBeInTheDocument();
      expect(screen.queryByText(/^File:/)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument();
    });
  });
});
