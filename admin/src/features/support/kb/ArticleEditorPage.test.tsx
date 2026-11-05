import { setToken, type AdminSupportKbArticle } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { renderRoute, settled, stubApi, type FakeReply } from '../../../test/fakeApi';
import { ArticleEditorPage } from './ArticleEditorPage';

const GROUPS: FakeReply = {
  status: 200,
  body: [
    { id: 'g1', name: 'Tài khoản', position: 1, articleCount: 1 },
    { id: 'g2', name: 'Credit', position: 2, articleCount: 0 },
  ],
};

function article(overrides: Partial<AdminSupportKbArticle> = {}): AdminSupportKbArticle {
  return {
    id: 'a1',
    slug: 'doi-mat-khau',
    title: 'Đổi mật khẩu',
    body: 'Vào **Tài khoản** để đổi.',
    status: 'draft',
    groupId: 'g1',
    updatedAt: '2026-10-19T07:00:00Z',
    ...overrides,
  };
}

function conflict(code: string): FakeReply {
  return { status: 409, body: { status: 409, code, detail: `server says ${code}` } };
}

function renderEditor(url: string) {
  return renderRoute(
    '/support/articles/:articleId',
    url,
    <ArticleEditorPage />,
    <Route path="/support/articles" element={<p>Article list</p>} />,
  );
}

beforeEach(() => setToken('admin-token-kb-editor', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('ArticleEditorPage', () => {
  it('shows "Đường dẫn này đã được dùng" on the slug field when the save answers 409 slug-taken', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'POST /api/v1/admin/support/kb/articles': conflict('slug-taken'),
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/new');

    await user.type(await screen.findByLabelText('Tiêu đề'), 'Nạp credit');
    await user.type(screen.getByLabelText('Đường dẫn'), 'nap-credit');
    await user.selectOptions(screen.getByLabelText('Chủ đề'), 'g2');
    await user.type(screen.getByLabelText('Nội dung (Markdown)'), 'Vào trang Credit.');
    await user.click(screen.getByRole('button', { name: 'Tạo bài' }));

    const slug = screen.getByLabelText('Đường dẫn');
    await waitFor(() => expect(slug).toHaveAttribute('aria-invalid', 'true'));
    expect(slug).toHaveAccessibleDescription(/Đường dẫn này đã được dùng/);
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      title: 'Nạp credit',
      slug: 'nap-credit',
      groupId: 'g2',
      body: 'Vào trang Credit.',
    });
  });

  it.each([
    ['draft', ['Công bố', 'Lưu trữ'], ['Công bố lại']],
    ['published', ['Lưu trữ'], ['Công bố', 'Công bố lại']],
    ['archived', ['Công bố lại'], ['Công bố', 'Lưu trữ']],
  ] as const)('offers the status actions of a %s article', async (status, shown, hidden) => {
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article({ status }) },
    });
    renderEditor('/support/articles/a1');

    expect(await screen.findByDisplayValue('Đổi mật khẩu')).toBeInTheDocument();
    for (const name of shown) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }
    for (const name of hidden) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('publishes a draft and shows its new status', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article() },
      'POST /api/v1/admin/support/kb/articles/a1/publish': {
        status: 200,
        body: article({ status: 'published', publishedAt: '2026-10-19T07:05:00Z' }),
      },
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    await user.click(await screen.findByRole('button', { name: 'Công bố' }));

    expect(await screen.findByText('Trạng thái: Đã công bố')).toBeInTheDocument();
    expect(calls.some((call) => call.path === '/api/v1/admin/support/kb/articles/a1/publish')).toBe(
      true,
    );
    expect(screen.queryByRole('button', { name: 'Công bố' })).not.toBeInTheDocument();
  });

  it('saves a "Bắt đầu" position and shows the featured-full message when three are taken', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article() },
      'PUT /api/v1/admin/support/kb/articles/a1': conflict('featured-full'),
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    const position = await screen.findByLabelText('Vị trí Bắt đầu');
    expect(
      within(position)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Không phải bài Bắt đầu', 'Bắt đầu 1', 'Bắt đầu 2', 'Bắt đầu 3']);
    await user.selectOptions(position, '1');
    await user.click(screen.getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(position).toHaveAttribute('aria-invalid', 'true'));
    expect(position).toHaveAccessibleDescription('Tối đa 3 bài Bắt đầu — bỏ chọn một bài trước');
    expect(calls.find((call) => call.method === 'PUT')?.body).toMatchObject({
      featuredPosition: 1,
    });
  });

  it('previews the body with raw HTML as text and an unsafe link as plain text', async () => {
    stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderEditor('/support/articles/new');

    await user.click(await screen.findByLabelText('Nội dung (Markdown)'));
    await user.paste(
      '# Tiêu đề\n\n<img src=x onerror=alert(1)> [x](javascript:alert(1)) [web](https://nodi.vn)',
    );

    const preview = screen.getByRole('region', { name: 'Xem trước' });
    expect(within(preview).getByRole('heading', { name: 'Tiêu đề' })).toBeInTheDocument();
    expect(preview).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(preview.querySelector('img')).toBeNull();
    expect(within(preview).queryByRole('link', { name: 'x' })).not.toBeInTheDocument();
    expect(within(preview).getByRole('link', { name: 'web' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    );
  });

  it('renders protocol-relative links as text, images as their alt text, and opens relative links in a new tab', async () => {
    stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderEditor('/support/articles/new');

    await user.click(await screen.findByLabelText('Nội dung (Markdown)'));
    await user.paste(
      '[a](//evil.com) [b](/\\evil.com) [c](/settings) ![Ảnh chụp](https://cdn.example/x.png)',
    );

    const preview = screen.getByRole('region', { name: 'Xem trước' });
    expect(within(preview).queryByRole('link', { name: 'a' })).not.toBeInTheDocument();
    // The parser percent-encodes the backslash, so the link stays on this host.
    expect(within(preview).getByRole('link', { name: 'b' })).toHaveAttribute(
      'href',
      '/%5Cevil.com',
    );
    const relative = within(preview).getByRole('link', { name: 'c' });
    expect(relative).toHaveAttribute('href', '/settings');
    expect(relative).toHaveAttribute('target', '_blank');
    expect(relative).toHaveAttribute('rel', 'noopener noreferrer');
    expect(preview.querySelector('img')).toBeNull();
    expect(preview).toHaveTextContent('Ảnh chụp');
  });

  it('archives a "Bắt đầu" article, clears its position and saves without one', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': {
        status: 200,
        body: article({ status: 'published', featuredPosition: 2 }),
      },
      'POST /api/v1/admin/support/kb/articles/a1/archive': {
        status: 200,
        body: article({ status: 'archived' }),
      },
      'PUT /api/v1/admin/support/kb/articles/a1': {
        status: 200,
        body: article({ status: 'archived', title: 'Đổi mật khẩu cũ' }),
      },
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    expect(await screen.findByLabelText('Vị trí Bắt đầu')).toHaveDisplayValue('Bắt đầu 2');
    await user.click(screen.getByRole('button', { name: 'Lưu trữ' }));

    expect(await screen.findByText('Trạng thái: Đã lưu trữ')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đã lưu trữ');
    expect(screen.getByLabelText('Vị trí Bắt đầu')).toHaveDisplayValue('Không phải bài Bắt đầu');
    await user.type(screen.getByLabelText('Tiêu đề'), ' cũ');
    await user.click(screen.getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    expect(calls.find((call) => call.method === 'PUT')?.body).not.toHaveProperty(
      'featuredPosition',
    );
  });

  it('keeps typed text when a refetch finds the article archived elsewhere', async () => {
    let current = article({ status: 'published', featuredPosition: 1 });
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': () => ({ status: 200, body: current }),
    });
    const user = userEvent.setup();
    const { queryClient } = renderEditor('/support/articles/a1');

    const body = await screen.findByLabelText('Nội dung (Markdown)');
    await user.type(body, ' Thêm một dòng.');
    current = article({ status: 'archived' });
    await queryClient.invalidateQueries();

    expect(await screen.findByText('Trạng thái: Đã lưu trữ')).toBeInTheDocument();
    expect(screen.getByLabelText('Vị trí Bắt đầu')).toHaveDisplayValue('Không phải bài Bắt đầu');
    expect(body).toHaveValue('Vào **Tài khoản** để đổi. Thêm một dòng.');
  });

  it('keeps the form and typed text when a background refetch fails', async () => {
    let failing = false;
    stubApi({
      'GET /api/v1/admin/support/kb/groups': () =>
        failing ? { status: 500, body: { status: 500, detail: 'boom' } } : GROUPS,
    });
    const user = userEvent.setup();
    const { queryClient } = renderEditor('/support/articles/new');

    await user.type(await screen.findByLabelText('Tiêu đề'), 'Nạp credit');
    failing = true;
    await queryClient.invalidateQueries();
    await settled(queryClient);

    expect(screen.getByLabelText('Tiêu đề')).toHaveValue('Nạp credit');
    expect(screen.queryByText('Không tải được dữ liệu.')).not.toBeInTheDocument();
  });

  it('maps a validation problem that carries only type and errors onto its field', async () => {
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article() },
      'PUT /api/v1/admin/support/kb/articles/a1': {
        status: 400,
        body: {
          status: 400,
          type: 'https://flow-cortex.dev/problems/validation-failed',
          errors: { title: 'must not be blank' },
        },
      },
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    await user.click(await screen.findByRole('button', { name: 'Lưu' }));

    const title = screen.getByLabelText('Tiêu đề');
    await waitFor(() => expect(title).toHaveAttribute('aria-invalid', 'true'));
    expect(title).toHaveAccessibleDescription('Tiêu đề cần 1–150 ký tự');
  });

  it('says the article is gone when the save answers 404 article-not-found', async () => {
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article() },
      'PUT /api/v1/admin/support/kb/articles/a1': {
        status: 404,
        body: { status: 404, code: 'article-not-found', detail: 'gone' },
      },
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    await user.click(await screen.findByRole('button', { name: 'Lưu' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bài viết này không còn tồn tại.');
  });

  it('marks the group field and reloads the groups when the save answers 404 group-not-found', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'GET /api/v1/admin/support/kb/articles/a1': { status: 200, body: article() },
      'PUT /api/v1/admin/support/kb/articles/a1': {
        status: 404,
        body: { status: 404, code: 'group-not-found', detail: 'gone' },
      },
    });
    const user = userEvent.setup();
    renderEditor('/support/articles/a1');

    await user.click(await screen.findByRole('button', { name: 'Lưu' }));

    const group = screen.getByLabelText('Chủ đề');
    await waitFor(() => expect(group).toHaveAttribute('aria-invalid', 'true'));
    expect(group).toHaveAccessibleDescription('Chọn một chủ đề');
    await waitFor(() =>
      expect(calls.filter((call) => call.path === '/api/v1/admin/support/kb/groups')).toHaveLength(
        2,
      ),
    );
  });
});
