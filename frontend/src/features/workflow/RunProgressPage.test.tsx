import {
  setToken,
  type RunResponse,
  type RunStepResponse,
  type ScriptRevisionResponse,
} from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
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
  // jsdom has no layout, so it has no scrollIntoView either.
  const scrollIntoView = vi.fn();
  beforeEach(() => {
    setToken('t', true);
    scrollIntoView.mockClear();
    Object.assign(Element.prototype, { scrollIntoView });
  });
  afterEach(() => {
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
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
    expect(screen.getByRole('button', { name: /Inspect the file/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
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
    const user = userEvent.setup();
    renderRun(completedPipeline());

    await screen.findByText('Sample rate');
    const panel = within(screen.getByRole('region', { name: 'Extract source audio' }));
    expect(panel.getByText('16000')).toBeInTheDocument();
    expect(panel.getByText('1')).toBeInTheDocument();
    expect(panel.getByText('pcm_s16le')).toBeInTheDocument();

    expect(
      screen.getByRole('button', { name: 'Download extracted audio (WAV)' }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Inspect the file/ }));
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
      const review = screen.getByRole('button', { name: /Review/ });
      expect(review).toHaveTextContent('Needs you');
      expect(review).toHaveAttribute('aria-pressed', 'true');
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

    it('says the approval failed when the request itself broke', async () => {
      const user = userEvent.setup();
      renderWaiting({
        [APPROVE]: { status: 500, body: { status: 500, detail: 'boom' } },
      });

      await screen.findByLabelText('Content to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Could not approve this step');
      expect(screen.getByRole('button', { name: 'Approve' })).toHaveAttribute('aria-busy', 'false');
    });

    it('says the step was already decided when the approval loses the race', async () => {
      const user = userEvent.setup();
      renderWaiting({
        [APPROVE]: {
          status: 409,
          body: { status: 409, detail: 'This step is no longer waiting for review' },
        },
      });

      await screen.findByLabelText('Content to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'This step is no longer waiting for review',
      );
    });

    it('shows no review for a finished run', async () => {
      const { calls, queryClient } = renderRun(completedCheck());

      await screen.findByRole('heading', { name: 'Run completed' });
      await settled(queryClient);
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
      expect(calls.some((call) => call.path === TASKS_PATH)).toBe(false);
    });
  });

  describe('a product video script checkpoint', () => {
    const REVISIONS = '/api/v1/review-tasks/t1/revisions';
    const APPROVE = 'POST /api/v1/review-tasks/t1/approve';
    const SCRIPT_TASK = {
      id: 't1',
      runId: 'r1',
      runStepId: 's2',
      reviewType: 'APPROVAL_CHECKPOINT',
      status: 'pending',
      originalPayload: 'Draft caption',
      version: 3,
      currentRevisionId: 'rev1',
    };
    const REVISION = {
      id: 'rev1',
      runId: 'r1',
      reviewTaskId: 't1',
      revisionNumber: 1,
      content: 'Draft caption',
      createdBy: 'u1',
      createdAt: '2026-10-11T00:00:00Z',
    };

    /** Serves a script checkpoint whose revisions the test can grow as the reviewer edits. */
    function renderScript(
      revisions: ScriptRevisionResponse[],
      extra: Record<string, Handler> = {},
    ) {
      const calls = stubApi({
        'GET /api/v1/runs/r1': {
          status: 200,
          body: run(
            [
              step({
                type: 'generate_script',
                mode: 'remote',
                status: 'completed',
                output: 'Draft caption',
              }),
              step({
                id: 's2',
                stepOrder: 2,
                type: 'APPROVAL_CHECKPOINT',
                mode: 'inline',
                status: 'pending',
              }),
            ],
            { status: 'waiting_approval', input: 'Draft caption' },
          ),
        },
        'GET /api/v1/runs/r1/review-tasks': { status: 200, body: [SCRIPT_TASK] },
        [`GET ${REVISIONS}`]: { status: 200, body: revisions },
        ...extra,
      });
      renderRoute(
        '/workflows/:workflowId/runs/:runId',
        '/workflows/w1/runs/r1',
        <RunProgressPage />,
      );
      return calls;
    }

    it('lists the revisions and edits into a new one instead of a local draft', async () => {
      const user = userEvent.setup();
      const calls = renderScript([REVISION], {
        [`POST ${REVISIONS}`]: {
          status: 200,
          body: {
            ...REVISION,
            id: 'rev2',
            revisionNumber: 2,
            content: 'Kịch bản mới',
            reviewTaskVersion: 4,
          },
        },
      });

      expect(await screen.findByLabelText('Script to approve')).toHaveValue('Draft caption');
      expect(screen.getByRole('list', { name: 'Script revisions' })).toHaveTextContent(
        'Revision 1',
      );

      await user.clear(screen.getByLabelText('Script to approve'));
      await user.type(screen.getByLabelText('Script to approve'), 'Kịch bản mới');
      await user.click(screen.getByRole('button', { name: 'Save revision' }));

      await waitFor(() =>
        expect(calls).toContainEqual({
          method: 'POST',
          path: REVISIONS,
          body: { content: 'Kịch bản mới', expectedVersion: 3 },
        }),
      );
    });

    it('approves the revision it is showing, not the text in the box', async () => {
      const user = userEvent.setup();
      const calls = renderScript([REVISION], {
        [APPROVE]: {
          status: 200,
          body: { ...SCRIPT_TASK, status: 'approved', approvedRevisionId: 'rev1' },
        },
      });

      await screen.findByLabelText('Script to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      await waitFor(() =>
        expect(calls).toContainEqual({
          method: 'POST',
          path: '/api/v1/review-tasks/t1/approve',
          body: { revisionId: 'rev1', expectedVersion: 3 },
        }),
      );
    });

    it('says the script moved on when a stale tab loses the approval', async () => {
      const user = userEvent.setup();
      renderScript([REVISION], {
        [APPROVE]: { status: 409, body: { status: 409, detail: 'Revision is no longer current' } },
      });

      await screen.findByLabelText('Script to approve');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'This script moved on in another tab.',
      );
    });

    it('marks an unsaved edit and keeps the save button live only while dirty', async () => {
      const user = userEvent.setup();
      renderScript([REVISION]);

      await screen.findByLabelText('Script to approve');
      expect(screen.queryByText(/Unsaved changes/)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Save revision' })).toBeDisabled();

      await user.type(screen.getByLabelText('Script to approve'), '!');

      expect(await screen.findByText(/Unsaved changes/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Save revision' })).toBeEnabled();
    });

    it('does not create a duplicate revision when save is clicked without changes', async () => {
      const user = userEvent.setup();
      const calls = renderScript([REVISION]);

      await screen.findByLabelText('Script to approve');
      await user.click(screen.getByRole('button', { name: 'Save revision' }));

      expect(calls.some((call) => call.method === 'POST' && call.path === REVISIONS)).toBe(false);
    });

    it('does not approve an older revision while the editor has unsaved changes', async () => {
      const user = userEvent.setup();
      const calls = renderScript([REVISION]);

      await screen.findByLabelText('Script to approve');
      await user.type(screen.getByLabelText('Script to approve'), '!');
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Approve' })).toHaveAttribute(
          'aria-disabled',
          'true',
        ),
      );
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(calls.some((call) => call.method === 'POST' && call.path.endsWith('/approve'))).toBe(
        false,
      );
    });

    it('warns before leaving the page while an edit is unsaved', async () => {
      const user = userEvent.setup();
      renderScript([REVISION]);

      await screen.findByLabelText('Script to approve');
      await user.type(screen.getByLabelText('Script to approve'), '!');

      const leaving = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(leaving);
      expect(leaving.defaultPrevented).toBe(true);
    });

    it('keeps the reviewer on the page when an internal link would discard unsaved changes', async () => {
      const user = userEvent.setup();
      const confirm = vi.fn(() => false);
      vi.stubGlobal('confirm', confirm);
      renderScript([REVISION]);

      await screen.findByLabelText('Script to approve');
      await user.type(screen.getByLabelText('Script to approve'), '!');
      await user.click(screen.getByRole('link', { name: 'Back to workflow' }));

      expect(confirm).toHaveBeenCalledOnce();
      expect(screen.getByLabelText('Script to approve')).toBeInTheDocument();
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

  describe('the pipeline and its detail panel', () => {
    const TASKS_PATH = '/api/v1/runs/r1/review-tasks';
    const APPROVED_CONTENT = 'https://www.douyin.com/video/7123456789';
    const node = (name: RegExp) => screen.getByRole('button', { name });

    function checkpointRun(
      checkpoint: Partial<RunStepResponse>,
      overrides: Partial<RunResponse>,
    ): RunResponse {
      return run(
        [
          step({ type: 'echo', mode: 'inline', status: 'completed', output: 'Draft caption' }),
          step({
            id: 's2',
            stepOrder: 2,
            type: 'APPROVAL_CHECKPOINT',
            mode: 'inline',
            ...checkpoint,
          }),
        ],
        overrides,
      );
    }

    it('opens a finished run on its last step and shows another step after one activation', async () => {
      const user = userEvent.setup();
      renderRun(completedPipeline());

      await screen.findByRole('region', { name: 'Extract source audio' });
      expect(node(/Extract source audio/)).toHaveAttribute('aria-pressed', 'true');
      expect(
        within(screen.getByRole('region', { name: 'Extract source audio' })).getByText(
          'Sample rate',
        ),
      ).toBeInTheDocument();
      expect(screen.getByText('Select a step to see its result.')).toBeInTheDocument();

      await user.click(node(/Inspect the file/));

      const panel = screen.getByRole('region', { name: 'Inspect the file' });
      expect(node(/Inspect the file/)).toHaveAttribute('aria-pressed', 'true');
      expect(node(/Extract source audio/)).toHaveAttribute('aria-pressed', 'false');
      expect(within(panel).getByText('Duration')).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Show raw' })).toBeInTheDocument();
      expect(
        within(panel).getByRole('button', { name: 'Download summary (.txt)' }),
      ).toBeInTheDocument();
      expect(
        within(panel).getByRole('button', { name: 'Download technical details (JSON file)' }),
      ).toBeInTheDocument();
    });

    it('reads a rejected run later: the checkpoint in error, the sentence and the code, no form', async () => {
      renderRun(
        checkpointRun(
          { status: 'error', errorCode: 'APPROVAL_REJECTED', errorMessage: 'Approval rejected' },
          { status: 'error' },
        ),
      );

      const panel = await screen.findByRole('region', { name: 'Review' });
      expect(node(/Review/)).toHaveTextContent('Error');
      expect(node(/Review/)).toHaveAttribute('aria-pressed', 'true');
      expect(within(panel).getByRole('alert')).toHaveTextContent(
        'You rejected this content, so the run stopped.',
      );
      expect(panel.querySelector('.run-step-code')?.textContent).toBe('APPROVAL_REJECTED');
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
    });

    it('reads an approved run later: the checkpoint completed and its content as text', async () => {
      const user = userEvent.setup();
      renderRun(
        checkpointRun({ status: 'completed', output: APPROVED_CONTENT }, { status: 'completed' }),
      );

      await user.click(await screen.findByRole('button', { name: /Review/ }));

      expect(node(/Review/)).toHaveTextContent('Completed');
      expect(
        within(screen.getByRole('region', { name: 'Review' })).getByText(APPROVED_CONTENT),
      ).toBeInTheDocument();
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
    });

    it('says in the panel when the content to approve cannot be loaded', async () => {
      renderRun(checkpointRun({ status: 'pending' }, { status: 'waiting_approval' }), {
        [`GET ${TASKS_PATH}`]: { status: 500, body: { status: 500 } },
      });

      const panel = await screen.findByRole('region', { name: 'Review' });
      expect(node(/Review/)).toHaveTextContent('Needs you');
      expect(await within(panel).findByRole('alert')).toHaveTextContent(
        'Could not load the step to review.',
      );
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
    });

    it('hides the form while another step is selected and brings it back, unsaved edit included, with the Review node', async () => {
      const user = userEvent.setup();
      renderRun(checkpointRun({ status: 'pending' }, { status: 'waiting_approval' }), {
        [`GET ${TASKS_PATH}`]: {
          status: 200,
          body: [{ id: 't1', runId: 'r1', status: 'pending', originalPayload: 'Draft caption' }],
        },
      });

      const content = await screen.findByLabelText('Content to approve');
      await user.clear(content);
      await user.type(content, 'Edited caption');
      await user.click(node(/Step 1/));
      expect(screen.getByLabelText('Content to approve')).not.toBeVisible();
      expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
      expect(
        screen.getByRole('heading', { name: 'Waiting for your approval' }),
      ).toBeInTheDocument();

      await user.click(node(/Review/));
      expect(screen.getByLabelText('Content to approve')).toBeVisible();
      expect(screen.getByLabelText('Content to approve')).toHaveValue('Edited caption');
      expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    });

    it('shows the next review with its own content when the run goes straight from one checkpoint to the next', async () => {
      const user = userEvent.setup();
      const checkpoint = (id: string, stepOrder: number, status: string) =>
        step({ id, stepOrder, type: 'APPROVAL_CHECKPOINT', mode: 'inline', status });
      const atFirst = run([checkpoint('s1', 1, 'pending'), checkpoint('s2', 2, 'pending')], {
        status: 'waiting_approval',
      });
      const atSecond = run([checkpoint('s1', 1, 'completed'), checkpoint('s2', 2, 'pending')], {
        status: 'waiting_approval',
      });
      const first = { id: 't1', runId: 'r1', status: 'pending', originalPayload: 'First draft' };
      const second = { id: 't2', runId: 'r1', status: 'pending', originalPayload: 'Second draft' };
      let approved = false;
      let readsSinceApproval = 0;
      renderRun(atFirst, {
        'GET /api/v1/runs/r1': () => ({ status: 200, body: approved ? atSecond : atFirst }),
        // The second review is created a moment after the first is approved.
        [`GET ${TASKS_PATH}`]: () => {
          if (!approved) return { status: 200, body: [first] };
          readsSinceApproval += 1;
          const decided = { ...first, status: 'approved' };
          return { status: 200, body: readsSinceApproval === 1 ? [decided] : [decided, second] };
        },
        'POST /api/v1/review-tasks/t1/approve': () => {
          approved = true;
          return { status: 200, body: { ...first, status: 'approved' } };
        },
      });

      const content = await screen.findByLabelText('Content to approve');
      await user.clear(content);
      await user.type(content, 'Edited first');
      await user.click(screen.getByRole('button', { name: 'Approve' }));

      expect(await screen.findByDisplayValue('Second draft', {}, { timeout: 4000 })).toBe(
        screen.getByLabelText('Content to approve'),
      );
    });

    it('shows the heading and no pipeline for a run with no steps', async () => {
      renderRun(run([], { status: 'completed' }));

      expect(await screen.findByRole('heading', { name: 'Run completed' })).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Steps' })).not.toBeInTheDocument();
      expect(screen.queryByRole('list')).not.toBeInTheDocument();
      expect(document.querySelector('.step-detail')).toBeNull();
    });

    it('offers the way back from the run itself when opened at /runs/<id>', async () => {
      const calls = stubApi({ 'GET /api/v1/runs/r1': { status: 200, body: completedCheck() } });
      const { queryClient } = renderRoute('/runs/:runId', '/runs/r1', <RunProgressPage />);

      expect(await screen.findByRole('link', { name: 'Back to workflow' })).toHaveAttribute(
        'href',
        '/workflows/w1',
      );
      await settled(queryClient);
      expect(calls.map((call) => call.path)).toEqual(['/api/v1/runs/r1']);
    });

    it('announces the step the page selects after a poll, and nothing on first render', async () => {
      const user = userEvent.setup();
      let current = checkpointRun({ status: 'pending' }, { status: 'running' });
      stubApi({
        'GET /api/v1/runs/r1': () => ({ status: 200, body: current }),
        [`GET ${TASKS_PATH}`]: {
          status: 200,
          body: [{ id: 't1', runId: 'r1', status: 'pending', originalPayload: 'Draft caption' }],
        },
      });
      const { queryClient } = renderRoute(
        '/workflows/:workflowId/runs/:runId',
        '/workflows/w1/runs/r1',
        <RunProgressPage />,
      );
      const announcement = () => screen.getByRole('main').querySelector('[role="status"]');

      await screen.findByRole('region', { name: 'Step 1' });
      expect(announcement()).toBeEmptyDOMElement();
      await user.click(node(/Step 1/));
      expect(announcement()).toBeEmptyDOMElement();

      current = checkpointRun({ status: 'pending' }, { status: 'waiting_approval' });
      await queryClient.invalidateQueries();

      await waitFor(() => expect(node(/Review/)).toHaveAttribute('aria-pressed', 'true'));
      expect(announcement()).toHaveTextContent('Review, Needs you');
    });

    it('scrolls the panel into view when a node is activated and never on first render', async () => {
      const user = userEvent.setup();
      const scrolled: Element[] = [];
      scrollIntoView.mockImplementation(function (this: Element) {
        scrolled.push(this);
      });
      renderRun(completedPipeline());

      await screen.findByRole('region', { name: 'Extract source audio' });
      expect(scrollIntoView).not.toHaveBeenCalled();

      await user.click(node(/Inspect the file/));

      expect(scrollIntoView).toHaveBeenCalledTimes(1);
      expect(scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ block: 'nearest' }));
      expect(scrolled[0]).toBe(screen.getByRole('region', { name: 'Inspect the file' }));
    });

    it('scrolls without animation when the visitor asks for reduced motion', async () => {
      const user = userEvent.setup();
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: query === '(prefers-reduced-motion: reduce)',
        media: query,
      }));
      renderRun(completedPipeline());

      await screen.findByRole('region', { name: 'Extract source audio' });
      await user.click(node(/Inspect the file/));

      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', behavior: 'auto' });
    });

    it('announces the step the member picks', async () => {
      const user = userEvent.setup();
      renderRun(completedPipeline());

      await screen.findByRole('region', { name: 'Extract source audio' });
      await user.click(node(/Inspect the file/));

      expect(screen.getByRole('main').querySelector('[role="status"]')).toHaveTextContent(
        'Inspect the file, Completed',
      );
    });

    it('does not bring a pick back after the run waited for approval and then continued', async () => {
      const user = userEvent.setup();
      const echo = (id: string, stepOrder: number, status: string) =>
        step({ id, stepOrder, type: 'echo', mode: 'inline', status, output: `out ${id}` });
      const checkpoint = (status: string) =>
        step({ id: 's3', stepOrder: 3, type: 'APPROVAL_CHECKPOINT', mode: 'inline', status });
      let current = run([
        echo('s1', 1, 'completed'),
        echo('s2', 2, 'running'),
        checkpoint('pending'),
      ]);
      stubApi({
        'GET /api/v1/runs/r1': () => ({ status: 200, body: current }),
        [`GET ${TASKS_PATH}`]: {
          status: 200,
          body: [{ id: 't1', runId: 'r1', status: 'pending', originalPayload: 'Draft caption' }],
        },
      });
      const { queryClient } = renderRoute(
        '/workflows/:workflowId/runs/:runId',
        '/workflows/w1/runs/r1',
        <RunProgressPage />,
      );

      await screen.findByRole('region', { name: 'Step 2' });
      await user.click(node(/Step 1/));
      expect(node(/Step 1/)).toHaveAttribute('aria-pressed', 'true');

      current = run(
        [echo('s1', 1, 'completed'), echo('s2', 2, 'completed'), checkpoint('pending')],
        {
          status: 'waiting_approval',
        },
      );
      await queryClient.invalidateQueries();
      await waitFor(() => expect(node(/Review/)).toHaveAttribute('aria-pressed', 'true'));

      current = run([
        echo('s1', 1, 'completed'),
        echo('s2', 2, 'completed'),
        checkpoint('completed'),
        echo('s4', 4, 'running'),
      ]);
      await queryClient.invalidateQueries();

      await waitFor(() => expect(node(/Step 4/)).toHaveAttribute('aria-pressed', 'true'));
      expect(node(/Step 1/)).toHaveAttribute('aria-pressed', 'false');
    });

    describe('opened by starting a run', () => {
      function LocationProbe() {
        const { state, search, hash } = useLocation();
        return <span data-testid="location">{JSON.stringify({ state, search, hash })}</span>;
      }

      const currentLocation = (): unknown =>
        JSON.parse(screen.getByTestId('location').textContent ?? '');

      function renderWithState(state: unknown, search = '', hash = '') {
        stubApi({ 'GET /api/v1/runs/r1': { status: 200, body: completedPipeline() } });
        const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
        render(
          <QueryClientProvider client={queryClient}>
            <MemoryRouter
              initialEntries={[{ pathname: '/workflows/w1/runs/r1', search, hash, state }]}
            >
              <Routes>
                <Route path="/workflows/:workflowId/runs/:runId" element={<RunProgressPage />} />
              </Routes>
              <LocationProbe />
            </MemoryRouter>
          </QueryClientProvider>,
        );
        return queryClient;
      }

      it('clears the focus request once the run has loaded, so a reload does not move focus', async () => {
        renderWithState({ focusHeading: true });

        await screen.findByRole('heading', { name: 'Run completed' });
        await waitFor(() => expect(currentLocation()).toMatchObject({ state: null }));
      });

      it('keeps the query string and the fragment when it clears the focus request', async () => {
        renderWithState({ focusHeading: true }, '?from=home', '#result');

        await screen.findByRole('heading', { name: 'Run completed' });
        await waitFor(() => expect(currentLocation()).toMatchObject({ state: null }));
        expect(currentLocation()).toMatchObject({ search: '?from=home', hash: '#result' });
      });

      it('puts focus on the heading once the run has loaded, and not again after a poll', async () => {
        const user = userEvent.setup();
        const queryClient = renderWithState({ focusHeading: true });

        const heading = await screen.findByRole('heading', { name: 'Run completed' });
        await waitFor(() => expect(heading).toHaveFocus());

        await user.click(node(/Inspect the file/));
        await queryClient.invalidateQueries();
        await settled(queryClient);
        expect(node(/Inspect the file/)).toHaveFocus();
      });

      it('leaves focus alone on a direct load', async () => {
        renderWithState(null);

        const heading = await screen.findByRole('heading', { name: 'Run completed' });
        expect(heading).not.toHaveFocus();
        expect(heading).toHaveAttribute('tabindex', '-1');
      });
    });
  });

  describe('stopping a run', () => {
    const CANCEL = 'POST /api/v1/runs/r1/cancel';
    const FEATURES_ON = {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: true,
      credits: false,
    };
    const stopButton = () => screen.findByRole('button', { name: 'Stop workflow' });
    const cancelCalls = (calls: { method: string; path: string }[]) =>
      calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/runs/r1/cancel');

    /** jsdom has no `showModal`/`close`; Escape fires `cancel` the way a browser does. */
    function stubModalDialog() {
      const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
      Object.defineProperties(proto, {
        showModal: {
          configurable: true,
          value(this: HTMLDialogElement) {
            this.setAttribute('open', '');
          },
        },
        close: {
          configurable: true,
          value(this: HTMLDialogElement) {
            if (!this.hasAttribute('open')) return;
            this.removeAttribute('open');
            this.dispatchEvent(new Event('close'));
          },
        },
      });
      const onEscape = (event: KeyboardEvent) => {
        const open = document.querySelector('dialog[open]');
        if (event.key === 'Escape' && open instanceof HTMLDialogElement) {
          if (open.dispatchEvent(new Event('cancel', { cancelable: true }))) open.close();
        }
      };
      document.addEventListener('keydown', onEscape);
      return () => {
        document.removeEventListener('keydown', onEscape);
        delete proto.showModal;
        delete proto.close;
      };
    }

    function threeSteps(first: Partial<RunStepResponse>, later: string): RunStepResponse[] {
      return [
        step({ type: 'echo', mode: 'inline', ...first }),
        step({ id: 's2', stepOrder: 2, type: 'echo', mode: 'inline', status: later }),
        step({ id: 's3', stepOrder: 3, type: 'echo', mode: 'inline', status: later }),
      ];
    }

    const runningRun = (overrides: Partial<RunResponse> = {}) =>
      run(threeSteps({ status: 'running', pickedUpAt: '2026-10-01T10:00:01Z' }, 'pending'), {
        input: 'hello',
        ...overrides,
      });
    const cancellingRun = () =>
      run(threeSteps({ status: 'running', pickedUpAt: '2026-10-01T10:00:01Z' }, 'skipped'), {
        status: 'cancelling',
        input: 'hello',
      });

    /** Serves `before` until a cancel succeeds, then `after`. */
    function renderStoppable(
      before: RunResponse,
      cancel: Handler,
      after: RunResponse = before,
      features = FEATURES_ON,
    ) {
      let stopped = false;
      const calls = stubApi({
        'GET /api/v1/features': { status: 200, body: features },
        'GET /api/v1/runs/r1': () => ({ status: 200, body: stopped ? after : before }),
        [CANCEL]: async () => {
          const reply = typeof cancel === 'function' ? await cancel() : cancel;
          stopped = reply.status < 400;
          return reply;
        },
      });
      const { queryClient } = renderRoute(
        '/workflows/:workflowId/runs/:runId',
        '/workflows/w1/runs/r1',
        <RunProgressPage />,
      );
      return { calls, queryClient };
    }

    let restoreDialog: () => void;
    beforeEach(() => {
      restoreDialog = stubModalDialog();
    });
    afterEach(() => {
      restoreDialog();
    });

    it.each(['running', 'waiting_approval'])(
      'offers "Stop workflow" on a %s run',
      async (status) => {
        renderStoppable(runningRun({ status }), { status: 200, body: cancellingRun() });

        expect(await stopButton()).toBeEnabled();
      },
    );

    it.each(['completed', 'error', 'cancelling', 'cancelled'])(
      'offers no "Stop workflow" on a %s run',
      async (status) => {
        const { queryClient } = renderStoppable(runningRun({ status }), { status: 409 });

        await screen.findByRole('heading', { level: 1 });
        await settled(queryClient);
        expect(screen.queryByRole('button', { name: 'Stop workflow' })).not.toBeInTheDocument();
      },
    );

    it('offers no "Stop workflow" while stopping runs is switched off', async () => {
      const { queryClient } = renderStoppable(runningRun(), { status: 503 }, runningRun(), {
        ...FEATURES_ON,
        runCancellation: false,
      });

      await screen.findByRole('heading', { name: 'Run in progress' });
      await settled(queryClient);
      expect(screen.queryByRole('button', { name: 'Stop workflow' })).not.toBeInTheDocument();
    });

    it('asks first, and "Keep running" or Escape closes the question without stopping', async () => {
      const user = userEvent.setup();
      const { calls } = renderStoppable(runningRun(), { status: 200, body: cancellingRun() });

      await user.click(await stopButton());
      expect(await screen.findByRole('dialog', { name: 'Stop this workflow?' })).toHaveTextContent(
        "The current step will finish, then the run stops. Remaining steps won't run and won't be charged. Results of finished steps are kept.",
      );
      expect(screen.getByRole('button', { name: 'Keep running' })).toHaveFocus();
      await user.click(screen.getByRole('button', { name: 'Keep running' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Stop workflow' })).toHaveFocus();

      await user.click(screen.getByRole('button', { name: 'Stop workflow' }));
      await screen.findByRole('dialog', { name: 'Stop this workflow?' });
      await user.keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Stop workflow' })).toHaveFocus();
      expect(cancelCalls(calls)).toHaveLength(0);
      expect(screen.getByRole('heading', { name: 'Run in progress' })).toBeInTheDocument();
    });

    it('shows what the run has been charged so far when the amount is known', async () => {
      const user = userEvent.setup();
      renderStoppable(runningRun({ chargedCredits: 60 }), { status: 200, body: cancellingRun() });

      await user.click(await stopButton());

      expect(await screen.findByRole('dialog')).toHaveTextContent(
        'Charged for this run so far: 60 credits',
      );
    });

    it('shows no charged line when the amount is unknown', async () => {
      const user = userEvent.setup();
      const unknownCharge = { ...runningRun(), chargedCredits: null } as unknown as RunResponse;
      renderStoppable(unknownCharge, { status: 200, body: cancellingRun() });

      await user.click(await stopButton());

      expect(await screen.findByRole('dialog')).not.toHaveTextContent(/Charged for this run/);
    });

    it('sends one stop for a double press, then reads "Stopping…" with the later steps skipped and keeps polling', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      onTestFinished(() => {
        vi.useRealTimers();
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) });
      const { calls } = renderStoppable(
        runningRun(),
        { status: 200, body: cancellingRun() },
        cancellingRun(),
      );

      await user.click(await stopButton());
      await screen.findByRole('dialog', { name: 'Stop this workflow?' });
      await user.dblClick(screen.getByRole('button', { name: 'Stop' }));

      const heading = await screen.findByRole('heading', { name: 'Stopping…' });
      expect(cancelCalls(calls)).toHaveLength(1);
      await waitFor(() => expect(heading).toHaveFocus());
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Stop workflow' })).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Step 2/ })).toHaveTextContent('Skipped');
      expect(screen.getByRole('button', { name: /Step 3/ })).toHaveTextContent('Skipped');

      const reads = calls.filter((call) => call.path === '/api/v1/runs/r1').length;
      await vi.advanceTimersByTimeAsync(2500);
      expect(calls.filter((call) => call.path === '/api/v1/runs/r1').length).toBeGreaterThan(reads);
      expect(cancelCalls(calls)).toHaveLength(1);
    });

    it('says the stop failed when the server cannot take it, and lets the member try again', async () => {
      const user = userEvent.setup();
      const { calls } = renderStoppable(runningRun(), {
        status: 503,
        body: { status: 503, detail: 'This feature is turned off' },
      });

      await user.click(await stopButton());
      await user.click(await screen.findByRole('button', { name: 'Stop' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        "Couldn't stop the run. Try again.",
      );
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { name: 'Run in progress' })).toBeInTheDocument();
      const again = screen.getByRole('button', { name: 'Stop workflow' });
      expect(again).toHaveFocus();
      await user.click(again);
      expect(
        await screen.findByRole('dialog', { name: 'Stop this workflow?' }),
      ).toBeInTheDocument();
      expect(cancelCalls(calls)).toHaveLength(1);
    });

    it('says the stop failed when the request never reaches the server', async () => {
      const user = userEvent.setup();
      renderStoppable(runningRun(), () => Promise.reject(new TypeError('Failed to fetch')));

      await user.click(await stopButton());
      await user.click(await screen.findByRole('button', { name: 'Stop' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        "Couldn't stop the run. Try again.",
      );
      expect(screen.getByRole('button', { name: 'Stop workflow' })).toBeInTheDocument();
    });

    it('stops a run waiting for approval at once and removes the review', async () => {
      const user = userEvent.setup();
      const waiting = run(
        [
          step({ type: 'echo', mode: 'inline', status: 'completed', output: 'Draft caption' }),
          step({
            id: 's2',
            stepOrder: 2,
            type: 'APPROVAL_CHECKPOINT',
            mode: 'inline',
            status: 'pending',
          }),
          step({ id: 's3', stepOrder: 3, type: 'echo', mode: 'inline', status: 'pending' }),
        ],
        { status: 'waiting_approval', input: 'Draft caption' },
      );
      const cancelled: RunResponse = {
        ...waiting,
        status: 'cancelled',
        steps: (waiting.steps ?? []).map((each) =>
          each.status === 'pending' ? { ...each, status: 'skipped' } : each,
        ),
      };
      renderStoppable(waiting, { status: 200, body: cancelled }, cancelled);

      await user.click(await stopButton());
      await user.click(await screen.findByRole('button', { name: 'Stop' }));

      expect(await screen.findByRole('heading', { name: 'Run cancelled' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Review/ })).toHaveTextContent('Skipped');
      expect(screen.getByRole('button', { name: /Step 3/ })).toHaveTextContent('Skipped');
      expect(screen.queryByLabelText('Content to approve')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    });

    it('keeps the download of a step that finished before the run was cancelled', async () => {
      const finished = completedPipeline();
      const [first, second] = finished.steps ?? [];
      const skipped = step({
        id: 's3',
        stepOrder: 3,
        type: 'echo',
        mode: 'inline',
        status: 'skipped',
      });
      const cancelled = run([first!, second!, skipped], { status: 'cancelled' });
      renderStoppable(cancelled, { status: 200, body: cancelled });

      expect(await screen.findByRole('heading', { name: 'Run cancelled' })).toBeInTheDocument();
      expect(
        await screen.findByRole('button', { name: 'Download extracted audio (WAV)' }),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Step 3/ })).toHaveTextContent('Skipped');
    });
  });
});
