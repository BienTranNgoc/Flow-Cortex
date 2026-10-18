import { setToken } from '@flow-cortex/api-client';
import type { MediaJobResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viMediaJobs from '../../locales/vi/mediaJobs.json';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { MediaJobsPage } from './MediaJobsPage';

const JOBS_PATH = 'GET /api/v1/admin/media/jobs';
const MINUTE = 60_000;
// The API sends `null` for a value the row does not have; the generated types call it optional.
const WIRE_NULL = null as never;
const at = (offsetMs: number) => new Date(Date.now() + offsetMs).toISOString();

function job(jobId: string, overrides: Partial<MediaJobResponse> = {}): MediaJobResponse {
  return {
    jobId,
    runId: 'run-1',
    stepOrder: 1,
    capability: 'video.transcode',
    status: 'running',
    attempt: 1,
    deliveryCount: 1,
    queueDeadline: at(5 * MINUTE),
    ...overrides,
  };
}

function page(items: MediaJobResponse[]): FakeReply {
  return { status: 200, body: { items, page: 0, size: 20, total: items.length } };
}

function detailOf(jobId: string): HTMLElement {
  const row = screen.getByText(jobId).closest('tr');
  if (!row) throw new Error(`no row for ${jobId}`);
  return within(row).getByTestId('job-detail');
}

beforeEach(() => setToken('admin-token-jobs', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

// Mirrors the reaper's own rule: unpicked → queue deadline; picked → lease or run deadline,
// and when both have passed the earlier one wins.
const MARKER_CASES: Array<[string, Partial<MediaJobResponse>, string, boolean]> = [
  ['unpicked before its queue deadline', { queueDeadline: at(MINUTE) }, 'waiting', false],
  ['unpicked past its queue deadline', { queueDeadline: at(-MINUTE) }, 'queue timeout pending', true],
  [
    'picked up before lease and run deadline',
    { pickedUpAt: at(-MINUTE), leaseUntil: at(MINUTE), runDeadline: at(10 * MINUTE) },
    'running',
    false,
  ],
  [
    'picked up past its lease',
    { pickedUpAt: at(-5 * MINUTE), leaseUntil: at(-2 * MINUTE), runDeadline: at(10 * MINUTE) },
    'lease expired',
    true,
  ],
  [
    'picked up past its run deadline',
    { pickedUpAt: at(-5 * MINUTE), leaseUntil: at(MINUTE), runDeadline: at(-MINUTE) },
    'run timeout pending',
    true,
  ],
  [
    'picked up past both, lease first',
    { pickedUpAt: at(-5 * MINUTE), leaseUntil: at(-2 * MINUTE), runDeadline: at(-MINUTE) },
    'lease expired',
    true,
  ],
  [
    'picked up past both, run deadline first',
    { pickedUpAt: at(-5 * MINUTE), leaseUntil: at(-MINUTE), runDeadline: at(-2 * MINUTE) },
    'run timeout pending',
    true,
  ],
  [
    'picked up past an unpicked-only queue deadline',
    { pickedUpAt: at(-5 * MINUTE), queueDeadline: at(-4 * MINUTE), leaseUntil: at(MINUTE) },
    'running',
    false,
  ],
  ['errored with a code', { status: 'error', errorCode: 'lease_expired', errorMessage: 'no heartbeat' }, 'lease_expired', false],
  ['errored without a code', { status: 'error', errorMessage: 'ffmpeg exited with status 1' }, 'ffmpeg exited with status 1', false],
  ['errored with neither', { status: 'error' }, '—', false],
  ['completed', { status: 'completed', queueDeadline: at(-MINUTE) }, '—', false],
  ['pending', { status: 'pending', queueDeadline: at(-MINUTE) }, '—', false],
  [
    'an older row with no deadlines or pickup',
    { queueDeadline: WIRE_NULL, leaseUntil: WIRE_NULL, pickedUpAt: WIRE_NULL, deliveryCount: WIRE_NULL },
    'waiting',
    false,
  ],
];

describe('MediaJobsPage', () => {
  it.each(MARKER_CASES)('derives the detail of a step %s', async (_name, overrides, label, isMarker) => {
    stubApi({ [JOBS_PATH]: page([job('job-under-test', overrides)]) });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    await screen.findByRole('table');

    const detail = detailOf('job-under-test');
    expect(detail).toHaveTextContent(label);
    expect(detail.querySelector('.job-marker') !== null).toBe(isMarker);
  });

  it('shows the engine message under the error code', async () => {
    stubApi({
      [JOBS_PATH]: page([
        job('job-failed', { status: 'error', errorCode: 'lease_expired', errorMessage: 'no heartbeat' }),
      ]),
    });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    await screen.findByRole('table');

    const detail = detailOf('job-failed');
    expect(within(detail).getByText('lease_expired')).toBeInTheDocument();
    expect(within(detail).getByText('no heartbeat')).toBeInTheDocument();
  });

  it('shows the full job id as selectable text, the attempt and the delivery count', async () => {
    const jobId = '0b7c5c6e-3d0e-4f6a-9a51-2f1d4e8c9b10';
    stubApi({ [JOBS_PATH]: page([job(jobId, { attempt: 1, deliveryCount: 2 })]) });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);

    const id = await screen.findByText(jobId);
    expect(id.tagName).toBe('CODE');
    expect(id.closest('a, button')).toBeNull();
    const row = id.closest('tr') as HTMLElement;
    expect(within(row).getByTestId('job-attempt')).toHaveTextContent('1');
    expect(within(row).getByTestId('job-deliveries')).toHaveTextContent('2');
    expect(screen.getByRole('columnheader', { name: 'Delivery count' })).toBeInTheDocument();
  });

  it('renders missing timestamps and counts of an older row as a dash', async () => {
    stubApi({
      [JOBS_PATH]: page([
        job('job-old', {
          queueDeadline: WIRE_NULL,
          leaseUntil: WIRE_NULL,
          pickedUpAt: WIRE_NULL,
          deliveryCount: WIRE_NULL,
        }),
      ]),
    });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    await screen.findByRole('table');

    const row = screen.getByText('job-old').closest('tr') as HTMLElement;
    expect(within(row).getByTestId('job-deliveries')).toHaveTextContent('—');
    for (const label of ['Picked up', 'Queue deadline', 'Lease until', 'Run deadline']) {
      expect(row.querySelector(`[data-label="${label}"]`)).toHaveTextContent('—');
    }
  });

  it('drops a marker once a refetch shows the reaper closed the step', async () => {
    let reaped = false;
    stubApi({
      [JOBS_PATH]: () =>
        page([
          reaped
            ? job('job-reaped', { status: 'error', errorCode: 'queue_timeout', queueDeadline: at(-MINUTE) })
            : job('job-reaped', { queueDeadline: at(-MINUTE) }),
        ]),
    });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    await screen.findByText('queue timeout pending');

    reaped = true;
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(detailOf('job-reaped')).toHaveTextContent('queue_timeout'));
    expect(screen.queryByText('queue timeout pending')).not.toBeInTheDocument();
  });

  it('filters by status and offers to clear a filter that matches nothing', async () => {
    const calls = stubApi({
      [JOBS_PATH]: () => (calls.at(-1)?.query.get('status') === 'pending' ? page([]) : page([job('job-1')])),
    });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'pending');
    expect(await screen.findByText('No media jobs match this filter')).toBeInTheDocument();
    expect(calls.at(-1)?.query.get('status')).toBe('pending');

    await userEvent.click(screen.getByRole('button', { name: 'Clear filter' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('has distinct loading, error and disabled states', async () => {
    stubApi({ [JOBS_PATH]: () => new Promise<FakeReply>(() => {}) });
    const loading = renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    expect(screen.getByText('Loading media jobs…').tagName).toBe('OUTPUT');
    loading.unmount();

    stubApi({ [JOBS_PATH]: { status: 500, body: { status: 500 } } });
    const failing = renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load media jobs.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    failing.unmount();

    stubApi({ [JOBS_PATH]: { status: 503, body: { status: 503 } } });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    expect(await screen.findByText('Admin console is disabled')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows status codes as sent in English and leaves the capability raw', async () => {
    stubApi({ [JOBS_PATH]: page([job('job-1', { status: 'pending' })]) });
    renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
    const row = (await screen.findByText('job-1')).closest('tr') as HTMLElement;

    expect(within(row).getByText('pending')).toBeInTheDocument();
    expect(within(row).getByText('video.transcode')).toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels a queued job in Vietnamese, leaving an unknown status and the capability as sent', async () => {
      stubApi({
        [JOBS_PATH]: page([
          job('job-queued', { status: 'pending' }),
          job('job-odd', { status: 'paused' }),
        ]),
      });
      renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
      const queued = (await screen.findByText('job-queued')).closest('tr') as HTMLElement;
      const odd = screen.getByText('job-odd').closest('tr') as HTMLElement;

      expect(within(queued).getByText(viMediaJobs.status.pending)).toBeInTheDocument();
      expect(within(queued).getByText('Chờ xử lý')).toBeInTheDocument();
      expect(within(queued).getByText('video.transcode')).toBeInTheDocument();
      expect(within(odd).getByText('paused')).toBeInTheDocument();
    });

    it('labels the status filter options in Vietnamese and keeps their values', async () => {
      stubApi({ [JOBS_PATH]: page([job('job-1')]) });
      renderRoute('/media-jobs', '/media-jobs', <MediaJobsPage />);
      await screen.findByRole('table');

      const options = within(screen.getByRole('combobox')).getAllByRole('option').slice(1);
      expect(options.map((option) => [option.getAttribute('value'), option.textContent])).toEqual([
        ['pending', viMediaJobs.status.pending],
        ['running', viMediaJobs.status.running],
        ['completed', viMediaJobs.status.completed],
        ['error', viMediaJobs.status.error],
      ]);
    });
  });
});
