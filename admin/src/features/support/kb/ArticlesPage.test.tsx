import { setToken, type AdminSupportKbArticleSummary } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../../test/fakeApi';
import { ArticlesPage } from './ArticlesPage';

const LIST_PATH = 'GET /api/v1/admin/support/kb/articles';

function summary(
  overrides: Partial<AdminSupportKbArticleSummary> = {},
): AdminSupportKbArticleSummary {
  return {
    id: 'a1',
    slug: 'nap-credit',
    title: 'Nạp credit',
    status: 'published',
    groupId: 'g2',
    groupName: 'Credit',
    featuredPosition: 1,
    publishedAt: '2026-10-18T07:00:00Z',
    updatedAt: '2026-10-19T07:00:00Z',
    ...overrides,
  };
}

function renderArticles() {
  return renderRoute('/support/articles', '/support/articles', <ArticlesPage />);
}

beforeEach(() => setToken('admin-token-kb-articles', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('ArticlesPage', () => {
  it('warns while fewer than three published "Bắt đầu" articles remain', async () => {
    stubApi({
      [LIST_PATH]: {
        status: 200,
        body: {
          items: [
            summary(),
            summary({ id: 'a2', slug: 'bat-dau', title: 'Bắt đầu từ đâu?', featuredPosition: 3 }),
          ],
          publishedFeaturedCount: 2,
        },
      },
    });
    renderArticles();

    expect(await screen.findByText('Chỉ còn 2/3 bài Bắt đầu')).toBeInTheDocument();
  });

  it('shows no warning once three published "Bắt đầu" articles exist', async () => {
    stubApi({
      [LIST_PATH]: { status: 200, body: { items: [summary()], publishedFeaturedCount: 3 } },
    });
    const { queryClient } = renderArticles();

    expect(await screen.findByRole('link', { name: 'Nạp credit' })).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByText(/Chỉ còn/)).not.toBeInTheDocument();
  });

  it('lists each article with its current group name, status and editor link, filterable by status', async () => {
    const archived: Partial<AdminSupportKbArticleSummary> = summary({
      id: 'a3',
      slug: 'cach-cu',
      title: 'Cách cũ',
      status: 'archived',
    });
    delete archived.featuredPosition;
    stubApi({
      [LIST_PATH]: {
        status: 200,
        body: {
          items: [summary({ groupName: 'Thanh toán' }), archived],
          publishedFeaturedCount: 1,
        },
      },
    });
    const user = userEvent.setup();
    renderArticles();

    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Thanh toán');
    expect(rows[0]).toHaveTextContent('Đã công bố');
    expect(rows[0]).toHaveTextContent('Bắt đầu 1');
    expect(rows[0]).toHaveTextContent('14:00 19/10/2026');
    expect(within(rows[0]!).getByRole('link', { name: 'Nạp credit' })).toHaveAttribute(
      'href',
      '/support/articles/a1',
    );
    expect(rows[1]).toHaveTextContent('Đã lưu trữ');

    const filter = screen.getByLabelText('Trạng thái');
    expect(
      within(filter)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Tất cả trạng thái', 'Nháp', 'Đã công bố', 'Đã lưu trữ']);
    expect(screen.getByRole('button', { name: 'Tải lại' })).toBeInTheDocument();
    await user.selectOptions(filter, 'archived');
    expect(within(screen.getByRole('table')).getAllByRole('row').slice(1)).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Cách cũ' })).toBeInTheDocument();
  });
});
