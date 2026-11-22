import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { NotificationsPage } from './NotificationsPage';

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: () => ({ close: () => undefined }),
}));

function items(from: number, to: number) {
  return Array.from({ length: to - from }, (_, index) => ({
    id: `n${from + index}`,
    type: 'tip',
    title: `Item ${from + index}`,
    body: '',
    read: true,
    createdAt: '2026-11-10T08:00:00Z',
  }));
}

function renderPage() {
  return renderRoute('/', '/', <NotificationsPage />);
}

describe('NotificationsPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('loads 20 then 25 and drops "load more" once everything shows', async () => {
    let calls = 0;
    const reply = (): FakeReply => {
      calls += 1;
      return calls === 1
        ? { status: 200, body: { items: items(0, 20), page: 0, size: 20, hasMore: true, unreadCount: 0 } }
        : { status: 200, body: { items: items(20, 25), page: 1, size: 20, hasMore: false, unreadCount: 0 } };
    };
    stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 0 } },
      'GET /api/v1/notifications': reply,
    });
    const user = userEvent.setup();

    renderPage();
    await screen.findByText('Item 0');
    expect(screen.getAllByText(/^Item /)).toHaveLength(20);

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(screen.getAllByText(/^Item /)).toHaveLength(25));
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('explains an empty Unread tab and switches tabs with the arrow keys', async () => {
    stubApi({
      'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 0 } },
      'GET /api/v1/notifications': {
        status: 200,
        body: { items: [], page: 0, size: 20, hasMore: false, unreadCount: 0 },
      },
    });
    const user = userEvent.setup();

    renderPage();
    const all = await screen.findByRole('tab', { name: 'All' });
    all.focus();
    await user.keyboard('{ArrowRight}');

    expect(screen.getByRole('tab', { name: 'Unread' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Unread' })).toHaveFocus();
    expect(await screen.findByText('Nothing unread. You are all caught up.')).toBeInTheDocument();
  });
});
