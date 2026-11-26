import { setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { UserActivitySection } from './UserActivitySection';

const USER_ID = '481ea74a-eeb6-4ad6-99c1-3c117f57d230';
const ACTIVITY_PATH = `GET /api/v1/users/${USER_ID}/activity`;

beforeEach(() => setToken('admin-token-user-activity', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

function item(index: number, extra: Record<string, unknown> = {}) {
  return {
    id: `item-${index}`,
    source: 'workflow',
    action: 'workflow.create',
    actorType: 'user',
    objectType: 'workflow',
    objectId: `w-${index}`,
    objectLabel: `Workflow ${index}`,
    occurredAt: '2026-11-20T10:00:00Z',
    ...extra,
  };
}

function renderSection() {
  return renderRoute(
    '/users/:userId',
    `/users/${USER_ID}`,
    <UserActivitySection userId={USER_ID} />,
  );
}

describe('UserActivitySection', () => {
  it('shows 50 rows newest first, then 60 and no control after Load more', async () => {
    const first = Array.from({ length: 50 }, (_, index) => item(index));
    const second = Array.from({ length: 10 }, (_, index) => item(50 + index));
    const calls = stubApi({
      [ACTIVITY_PATH]: () => ({
        status: 200,
        body: calls.length > 1 ? { items: second } : { items: first, nextCursor: 'cursor-1' },
      }),
    });
    renderSection();

    expect(await screen.findByText('Workflow 0')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(51);
    expect(calls[0]?.query.get('limit')).toBe('50');
    expect(calls[0]?.query.has('cursor')).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('Workflow 59')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(61);
    expect(calls[1]?.query.get('cursor')).toBe('cursor-1');
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('labels a known action and reads an unknown one as its raw value', async () => {
    stubApi({
      [ACTIVITY_PATH]: {
        status: 200,
        body: {
          items: [
            item(1),
            item(2, { source: 'admin_action', action: 'workflow.change_visibility' }),
          ],
        },
      },
    });
    renderSection();

    expect(await screen.findByText('Workflow created')).toBeInTheDocument();
    expect(screen.getByText('workflow.change_visibility')).toBeInTheDocument();
  });

  it('reads the member as "This member" and an admin by its label', async () => {
    stubApi({
      [ACTIVITY_PATH]: {
        status: 200,
        body: {
          items: [
            item(1),
            item(2, {
              source: 'admin_action',
              action: 'user.update',
              actorType: 'admin',
              actorLabel: 'Ops Lead',
              objectType: 'user',
              objectLabel: 'L*** T*** L***',
            }),
          ],
        },
      },
    });
    renderSection();

    const rows = await screen.findAllByRole('row');
    expect(within(rows[1] as HTMLElement).getByText('This member')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Ops Lead')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('L*** T*** L***')).toBeInTheDocument();
  });

  it('shows the empty state for a member with no items', async () => {
    stubApi({ [ACTIVITY_PATH]: { status: 200, body: { items: [] } } });
    renderSection();

    expect(await screen.findByText('No activity yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('says the load failed and recovers on Retry', async () => {
    let failing = true;
    stubApi({
      [ACTIVITY_PATH]: () =>
        failing
          ? { status: 500, body: { status: 500, detail: 'boom' } }
          : { status: 200, body: { items: [item(1)] } },
    });
    renderSection();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the activity.');
    failing = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Workflow 1')).toBeInTheDocument();
  });

  it('keeps the rows shown and says so when the next page fails', async () => {
    let page = 0;
    stubApi({
      [ACTIVITY_PATH]: () => {
        page += 1;
        return page === 1
          ? { status: 200, body: { items: [item(1)], nextCursor: 'cursor-1' } }
          : { status: 500, body: { status: 500, detail: 'boom' } };
      },
    });
    renderSection();

    await userEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load more activity.');
    expect(screen.getByText('Workflow 1')).toBeInTheDocument();
  });
});
