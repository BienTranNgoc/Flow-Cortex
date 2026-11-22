import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation } from 'react-router-dom';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { NotificationBell } from './NotificationBell';

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: () => ({ close: () => undefined }),
}));

const ITEMS = [
  {
    id: 'n1',
    type: 'welcome',
    title: 'Welcome to Nodi',
    body: 'Start with a workflow.',
    link: '/workflows',
    read: false,
    createdAt: '2026-11-10T08:00:00Z',
  },
  { id: 'n2', type: 'tip', title: 'A tip', body: '', read: true, createdAt: '2026-11-09T08:00:00Z' },
];

function page(items = ITEMS) {
  return { status: 200, body: { items, page: 0, size: 6, hasMore: false, unreadCount: 1 } };
}

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderBell() {
  return renderRoute('/', '/', <NotificationBell />);
}

describe('NotificationBell', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it.each([
    [0, /0 unread/, null],
    [7, /7 unread/, '7'],
    [140, /140 unread/, '99+'],
  ])('shows %i unread as %s', async (count, name, badge) => {
    stubApi({ 'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: count } } });

    const { queryClient } = renderBell();
    await settled(queryClient);

    expect(await screen.findByRole('button', { name })).toBeInTheDocument();
    if (badge) {
      expect(screen.getByText(badge)).toBeInTheDocument();
    } else {
      expect(document.querySelector('.notification-bell__badge')).toBeNull();
    }
  });

  it('marks an unread item read, closes the panel and opens its link', async () => {
    const calls = stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 1 } },
      'GET /api/v1/notifications': page(),
      'POST /api/v1/notifications/n1/read': { status: 204 },
    });
    const { queryClient } = renderBell();
    const user = userEvent.setup();
    await settled(queryClient);

    await user.click(screen.getByRole('button', { name: /unread/ }));
    await user.click(await screen.findByRole('button', { name: /Welcome to Nodi/ }));

    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/notifications/n1/read')).toBe(true));
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });

  it('navigates to an in-app link and not for an item without one', async () => {
    stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 0 } },
      'GET /api/v1/notifications': page(),
      'POST /api/v1/notifications/n1/read': { status: 204 },
    });
    const { queryClient } = renderRoute(
      '*',
      '/',
      <>
        <NotificationBell />
        <Where />
      </>,
    );
    const user = userEvent.setup();
    await settled(queryClient);

    await user.click(screen.getByRole('button', { name: /unread/ }));
    await user.click(await screen.findByRole('button', { name: /A tip/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/');

    await user.click(screen.getByRole('button', { name: /unread/ }));
    await user.click(await screen.findByRole('button', { name: /Welcome to Nodi/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/workflows');
  });

  it('shows an error with a retry when the list fails, and Escape returns focus to the bell', async () => {
    stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 2 } },
      'GET /api/v1/notifications': { status: 500, body: { status: 500 } },
    });
    const { queryClient } = renderBell();
    const user = userEvent.setup();
    await settled(queryClient);

    const bell = screen.getByRole('button', { name: /unread/ });
    await user.click(bell);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    expect(bell).toHaveFocus();
  });

  it('closes on a pointer press outside the panel', async () => {
    stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 1 } },
      'GET /api/v1/notifications': page(),
    });
    const { queryClient } = renderBell();
    const user = userEvent.setup();
    await settled(queryClient);

    await user.click(screen.getByRole('button', { name: /unread/ }));
    expect(await screen.findByRole('region')).toBeInTheDocument();
    await user.click(document.body);

    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });
});
