import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi, type RecordedCall } from '../../test/fakeApi';
import { AuditLogPage } from './AuditLogPage';

const ME_PATH = 'GET /api/v1/auth/me';
const LIST_PATH = 'GET /api/v1/admin/audit-log';
const FACETS_PATH = 'GET /api/v1/admin/audit-log/facets';
const EXPORT_PATH = 'GET /api/v1/admin/audit-log/export';

const VIEWER = staffMe('OPERATION_MANAGER', ['system.audit_log.view']);
const FACETS = {
  status: 200,
  body: { actions: ['user.delete', 'user.update'], resourceTypes: ['user'] },
};

function row(id: string, patch: Record<string, unknown> = {}) {
  return {
    id,
    occurredAt: '2026-11-15T10:00:00Z',
    actorUserId: 'mai-id',
    actorEmail: 'mai.tran@example.com',
    actorName: 'Mai Tran',
    action: 'user.update',
    resourceType: 'user',
    resourceId: 'r1',
    resourceLabel: 'L*** T*** L***',
    changeSummary: { role: 'MEMBER' },
    status: 'success',
    errorCode: null,
    ip: '203.0.113.7',
    userAgent: 'Mozilla',
    origin: 'http',
    ...patch,
  };
}

const pageOf = (items: unknown[], nextCursor: string | null = null) => ({
  status: 200,
  body: { items, nextCursor },
});

const listCalls = (calls: RecordedCall[]) =>
  calls.filter((call) => call.path.endsWith('/audit-log'));

beforeEach(() => setToken('admin-token-audit', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage(me: unknown, routes: Parameters<typeof stubApi>[0]) {
  const calls = stubApi({ [ME_PATH]: { status: 200, body: me }, [FACETS_PATH]: FACETS, ...routes });
  renderRoute('/audit', '/audit', <AuditLogPage />);
  return calls;
}

describe('AuditLogPage', () => {
  it('lists rows with time, actor, action, resource and status and shows no Export to a viewer without the export key', async () => {
    renderPage(VIEWER, {
      [LIST_PATH]: pageOf([row('a1'), row('a2', { status: 'failed', action: 'user.delete' })]),
    });

    const table = await screen.findByRole('table');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent),
    ).toEqual(['Time', 'Actor', 'Action', 'Resource', 'Status']);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Mai Tran');
    expect(rows[0]).toHaveTextContent('user.update');
    expect(rows[0]).toHaveTextContent('L*** T*** L***');
    expect(rows[1]).toHaveTextContent('Failed');
    expect(screen.getByRole('search', { name: 'Audit log filters' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });

  it('loads the next page from its cursor and drops Load more on the last page', async () => {
    const calls = renderPage(VIEWER, {
      [LIST_PATH]: () => ({ status: 200, body: { items: [row('a1')], nextCursor: 'c1' } }),
    });
    await screen.findByRole('table');
    expect(listCalls(calls)[0]?.query.get('size')).toBe('50');
    expect(listCalls(calls)[0]?.query.has('cursor')).toBe(false);

    stubApi({
      [ME_PATH]: { status: 200, body: VIEWER },
      [FACETS_PATH]: FACETS,
      [LIST_PATH]: pageOf([row('a2')]),
    });
    await userEvent.click(screen.getByRole('button', { name: 'Load 50 more' }));

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.queryByRole('button', { name: 'Load 50 more' })).not.toBeInTheDocument();
  });

  it('sends a status filter and exports with the same filters, then reports the saved file', async () => {
    const calls = renderPage(ADMIN_ME, {
      [LIST_PATH]: pageOf([row('a1'), row('a2', { status: 'failed' })]),
      [EXPORT_PATH]: { status: 200, body: 'Time,Actor' },
    });
    URL.createObjectURL = vi.fn(() => 'blob:audit');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'failed');
    await waitFor(() =>
      expect(listCalls(calls).some((call) => call.query.get('status') === 'failed')).toBe(true),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Downloaded audit-log.csv.');
    expect(click).toHaveBeenCalledTimes(1);
    const exported = calls.find((call) => call.path.endsWith('/export'));
    expect(exported?.query.get('status')).toBe('failed');
  });

  it('shows the refusal text of a 422 export and downloads nothing', async () => {
    renderPage(ADMIN_ME, {
      [LIST_PATH]: pageOf([row('a1')]),
      [EXPORT_PATH]: {
        status: 422,
        body: { status: 422, detail: 'Too many rows: 12 match, the limit is 10' },
      },
    });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many rows: 12 match');
    expect(click).not.toHaveBeenCalled();
  });

  it('falls back to its own text when a 503 export carries no detail', async () => {
    renderPage(ADMIN_ME, {
      [LIST_PATH]: pageOf([row('a1')]),
      [EXPORT_PATH]: { status: 503, body: { status: 503 } },
    });
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('could not record it');
  });

  it('filters by the actor of a row through a removable chip', async () => {
    const calls = renderPage(VIEWER, {
      [LIST_PATH]: pageOf([row('a1'), row('a2', { actorUserId: 'lan-id', actorName: 'Lan' })]),
    });
    await screen.findByRole('table');

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Show only rows by Mai Tran' })[0]!,
    );

    expect(await screen.findByText('Actor: Mai Tran')).toBeInTheDocument();
    await waitFor(() =>
      expect(listCalls(calls).some((call) => call.query.get('actorUserId') === 'mai-id')).toBe(
        true,
      ),
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Remove the actor filter (Mai Tran)' }),
    );

    expect(screen.queryByText('Actor: Mai Tran')).not.toBeInTheDocument();
    await waitFor(() => expect(listCalls(calls).at(-1)?.query.has('actorUserId')).toBe(false));
  });

  it('sends a search only from three characters', async () => {
    const calls = renderPage(VIEWER, { [LIST_PATH]: pageOf([row('a1')]) });
    await screen.findByRole('table');

    await userEvent.type(screen.getByLabelText('Search'), 'ma');
    expect(screen.getByText('Type at least 3 characters to search.')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 450));
    expect(listCalls(calls).every((call) => !call.query.has('q'))).toBe(true);

    await userEvent.type(screen.getByLabelText('Search'), 'i');
    await waitFor(() =>
      expect(listCalls(calls).some((call) => call.query.get('q') === 'mai')).toBe(true),
    );
  });

  it('refuses a reversed day range without asking the server', async () => {
    const calls = renderPage(VIEWER, { [LIST_PATH]: pageOf([row('a1')]) });
    await screen.findByRole('table');

    await userEvent.type(screen.getByLabelText('From'), '2026-11-16');
    await userEvent.type(screen.getByLabelText('To'), '2026-11-15');

    expect(await screen.findByText(/start day is after the end day/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(listCalls(calls).some((call) => call.query.has('from') && call.query.has('to'))).toBe(
      false,
    );
  });

  it('shows the empty state without Load more when the filters match nothing', async () => {
    renderPage(VIEWER, { [LIST_PATH]: pageOf([row('a1')]) });
    await screen.findByRole('table');
    stubApi({
      [ME_PATH]: { status: 200, body: VIEWER },
      [FACETS_PATH]: FACETS,
      [LIST_PATH]: pageOf([]),
    });

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'failed');

    expect(await screen.findByText('No audit rows match these filters.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load 50 more' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('opens the details of a clicked row and closes them with Escape', async () => {
    renderPage(VIEWER, { [LIST_PATH]: pageOf([row('a1', { errorCode: 'user.lastSuperAdmin' })]) });
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('cell', { name: /user\.update/ }));

    const panel = await screen.findByRole('complementary', { name: 'Audit row' });
    expect(panel).toHaveTextContent('user.lastSuperAdmin');
    expect(panel).toHaveTextContent('"role": "MEMBER"');
    expect(panel).toHaveTextContent('203.0.113.7');
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });

  it('shows an alert with a retry action when the log fails to load', async () => {
    const calls = renderPage(VIEWER, {
      [LIST_PATH]: { status: 500, body: { status: 500, detail: 'boom' } },
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The audit log could not be loaded.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(listCalls(calls).length).toBeGreaterThanOrEqual(2));
  });
});
