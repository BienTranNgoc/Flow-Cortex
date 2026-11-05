import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { stubApi, type Handler } from '../../test/fakeApi';
import { SupportWidget } from './SupportWidget';

const GROUPS = [
  {
    id: 'g2',
    name: 'Credit',
    position: 2,
    articles: [{ slug: 'nap-credit', title: 'Nạp credit' }],
  },
  {
    id: 'g1',
    name: 'Tài khoản',
    position: 1,
    articles: [
      { slug: 'doi-mat-khau', title: 'Đổi mật khẩu' },
      { slug: 'cach-cu', title: 'Cách cũ' },
    ],
  },
];

const BASE: Record<string, Handler> = {
  'GET /api/v1/support/kb/featured': { status: 200, body: [] },
  'GET /api/v1/support/settings': { status: 200, body: { assistantAvailable: true } },
};

async function openGuides(routes: Record<string, Handler>) {
  setToken('member', true);
  const calls = stubApi({ ...BASE, ...routes });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workflows']}>
        <SupportWidget />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Trợ giúp' }));
  await user.click(screen.getByRole('tab', { name: 'Hướng dẫn' }));
  return { user, calls, panel: screen.getByRole('dialog', { name: 'Trung tâm trợ giúp' }) };
}

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

afterEach(() => {
  setToken(null);
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('GuidesTab', () => {
  it('lists the published articles grouped by topic in the admin group order', async () => {
    const { panel } = await openGuides({
      'GET /api/v1/support/kb/groups': { status: 200, body: GROUPS },
    });

    const account = await within(panel).findByRole('list', { name: 'Tài khoản' });
    const credit = within(panel).getByRole('list', { name: 'Credit' });
    expect(within(account).getAllByRole('button')).toHaveLength(2);
    expect(within(credit).getAllByRole('button')).toHaveLength(1);
    expect(
      within(panel)
        .getAllByRole('heading', { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(['Tài khoản', 'Credit']);
  });

  it('reads "Chưa có bài hướng dẫn nào" with "Hỏi trợ lý" when nothing is published', async () => {
    const { user, panel } = await openGuides({
      'GET /api/v1/support/kb/groups': {
        status: 200,
        body: [{ id: 'g1', name: 'Tài khoản', position: 1, articles: [] }],
      },
    });

    expect(await within(panel).findByText('Chưa có bài hướng dẫn nào')).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Hỏi trợ lý' }));

    expect(within(panel).getByRole('tab', { name: 'Trợ lý' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('finds "Nạp credit" for "nap credit"', async () => {
    const { user, panel, calls } = await openGuides({
      'GET /api/v1/support/kb/groups': { status: 200, body: GROUPS },
      'GET /api/v1/support/kb/search': {
        status: 200,
        body: [{ slug: 'nap-credit', title: 'Nạp credit', groupName: 'Credit' }],
      },
    });

    await user.type(
      within(panel).getByRole('searchbox', { name: 'Tìm bài hướng dẫn' }),
      'nap credit',
    );

    const results = await within(panel).findByRole('list', { name: 'Kết quả tìm kiếm' });
    expect(within(results).getByRole('button', { name: /Nạp credit/ })).toBeInTheDocument();
    expect(
      calls.filter((call) => call.path === '/api/v1/support/kb/search').length,
    ).toBeGreaterThan(0);
  });

  it('sends no search for one character and shows the no-match state for "xyzzy"', async () => {
    const { user, panel, calls } = await openGuides({
      'GET /api/v1/support/kb/groups': { status: 200, body: GROUPS },
      'GET /api/v1/support/kb/search': { status: 200, body: [] },
    });
    const search = within(panel).getByRole('searchbox', { name: 'Tìm bài hướng dẫn' });

    await user.type(search, 'n');
    await pause(500);
    expect(calls.filter((call) => call.path === '/api/v1/support/kb/search')).toEqual([]);

    await user.clear(search);
    await user.type(search, 'xyzzy');

    expect(await within(panel).findByText('Không tìm thấy bài phù hợp')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Hỏi trợ lý' })).toBeInTheDocument();
  });

  it.each([
    ['cleared', ''],
    ['cut to one character', 'z'],
  ])('shows the groups again when a no-match query is %s', async (_label, rest) => {
    const { user, panel } = await openGuides({
      'GET /api/v1/support/kb/groups': { status: 200, body: GROUPS },
      'GET /api/v1/support/kb/search': { status: 200, body: [] },
    });
    const search = within(panel).getByRole('searchbox', { name: 'Tìm bài hướng dẫn' });
    await user.type(search, 'zzzxqq');
    expect(await within(panel).findByText('Không tìm thấy bài phù hợp')).toBeInTheDocument();

    await user.keyboard('{Control>}a{/Control}{Backspace}');
    if (rest) await user.type(search, rest);

    expect(within(panel).queryByText('Không tìm thấy bài phù hợp')).toBeNull();
    expect(within(panel).getByRole('list', { name: 'Tài khoản' })).toBeInTheDocument();
    await pause(500);
    expect(within(panel).queryByText('Không tìm thấy bài phù hợp')).toBeNull();
    expect(within(panel).getByRole('list', { name: 'Tài khoản' })).toBeInTheDocument();
  });

  it('reads "Bài viết này không còn được công bố" for an archived article, with a way back', async () => {
    const { user, panel } = await openGuides({
      'GET /api/v1/support/kb/groups': { status: 200, body: GROUPS },
      'GET /api/v1/support/kb/articles/cach-cu': {
        status: 404,
        body: {
          type: 'https://flow-cortex.dev/problems/article-not-found',
          code: 'article-not-found',
          status: 404,
        },
      },
    });

    await user.click(await within(panel).findByRole('button', { name: 'Cách cũ' }));

    expect(
      await within(panel).findByText('Bài viết này không còn được công bố'),
    ).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Về Hướng dẫn' }));
    expect(await within(panel).findByRole('list', { name: 'Tài khoản' })).toBeInTheDocument();
  });
});
