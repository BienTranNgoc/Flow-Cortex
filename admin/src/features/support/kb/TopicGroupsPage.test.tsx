import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../../test/fakeApi';
import { TopicGroupsPage } from './TopicGroupsPage';

const GROUPS: FakeReply = {
  status: 200,
  body: [
    { id: 'g1', name: 'Tài khoản', position: 1, articleCount: 2 },
    { id: 'g2', name: 'Credit', position: 2, articleCount: 1 },
  ],
};

beforeEach(() => setToken('admin-token-kb-groups', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('TopicGroupsPage', () => {
  it('shows the article count message when the group still holds articles', async () => {
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'DELETE /api/v1/admin/support/kb/groups/g2': {
        status: 409,
        body: { status: 409, code: 'group-not-empty', articleCount: 1, detail: 'still holds 1' },
      },
    });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.click(await screen.findByRole('button', { name: 'Xoá Credit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Nhóm còn 1 bài viết — chuyển bài sang nhóm khác trước',
    );
    expect(screen.getByText('Credit')).toBeInTheDocument();
  });

  it('reorders by sending every group in the new order', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'PUT /api/v1/admin/support/kb/groups/order': {
        status: 200,
        body: [
          { id: 'g2', name: 'Credit', position: 1, articleCount: 1 },
          { id: 'g1', name: 'Tài khoản', position: 2, articleCount: 2 },
        ],
      },
    });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.click(await screen.findByRole('button', { name: 'Đưa Credit lên' }));

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ ids: ['g2', 'g1'] }),
    );
    await waitFor(() =>
      expect(
        screen.getAllByRole('listitem').map((item) => item.querySelector('strong')?.textContent),
      ).toEqual(['Credit', 'Tài khoản']),
    );
    expect(screen.getByRole('button', { name: 'Đưa Credit lên' })).toHaveFocus();
    expect(screen.getByRole('status')).toHaveTextContent('Đã chuyển Credit lên vị trí 1');
  });

  it('renames a group', async () => {
    let renamed = false;
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': () =>
        renamed
          ? {
              status: 200,
              body: [
                { id: 'g1', name: 'Tài khoản', position: 1, articleCount: 2 },
                { id: 'g2', name: 'Thanh toán', position: 2, articleCount: 1 },
              ],
            }
          : GROUPS,
      'PUT /api/v1/admin/support/kb/groups/g2': () => {
        renamed = true;
        return {
          status: 200,
          body: { id: 'g2', name: 'Thanh toán', position: 2, articleCount: 1 },
        };
      },
    });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    const input = await screen.findByLabelText('Tên mới cho Credit');
    await user.clear(input);
    await user.type(input, 'Thanh toán');
    await user.click(within(input.closest('li')!).getByRole('button', { name: 'Đổi tên' }));

    expect(await screen.findByText('Thanh toán', { selector: 'strong' })).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ name: 'Thanh toán' });
    expect(screen.getByRole('status')).toHaveTextContent('Đã đổi tên thành Thanh toán');
  });

  it('says why a blank rename is not sent', async () => {
    const calls = stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    const input = await screen.findByLabelText('Tên mới cho Credit');
    await user.clear(input);
    await user.type(input, '   ');
    await user.click(within(input.closest('li')!).getByRole('button', { name: 'Đổi tên' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Tên chủ đề cần 1–60 ký tự');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);
  });

  it('says why a blank new group is not sent', async () => {
    const calls = stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.click(await screen.findByRole('button', { name: 'Thêm chủ đề' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Tên chủ đề cần 1–60 ký tự');
    expect(screen.getByLabelText('Tên chủ đề mới')).toHaveAccessibleDescription(
      'Tên chủ đề cần 1–60 ký tự',
    );
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('shows the name-taken message when a new group repeats a name', async () => {
    stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'POST /api/v1/admin/support/kb/groups': {
        status: 409,
        body: { status: 409, code: 'group-name-taken', detail: 'taken' },
      },
    });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.type(await screen.findByLabelText('Tên chủ đề mới'), 'Credit');
    await user.click(screen.getByRole('button', { name: 'Thêm chủ đề' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Đã có chủ đề tên này');
  });

  it('reloads the groups when the order no longer matches', async () => {
    const calls = stubApi({
      'GET /api/v1/admin/support/kb/groups': GROUPS,
      'PUT /api/v1/admin/support/kb/groups/order': {
        status: 400,
        body: { status: 400, code: 'group-order-mismatch', detail: 'mismatch' },
      },
    });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.click(await screen.findByRole('button', { name: 'Đưa Credit lên' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Danh sách chủ đề vừa thay đổi — đã tải lại thứ tự mới nhất',
    );
    await waitFor(() =>
      expect(
        calls.filter((call) => call.method === 'GET' && call.path.endsWith('/groups')),
      ).toHaveLength(2),
    );
  });
});
