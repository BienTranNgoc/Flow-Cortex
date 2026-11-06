import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../../lib/i18n';
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

    await user.click(await screen.findByRole('button', { name: 'Delete Credit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The topic still has 1 article — move it to another topic first',
    );
    expect(screen.getByText('Credit')).toBeInTheDocument();
  });

  describe('focus after deleting a topic', () => {
    const FIRST = { id: 'g1', name: 'Tài khoản', position: 1, articleCount: 0 };
    const SECOND = { id: 'g2', name: 'Credit', position: 2, articleCount: 0 };

    /** Renders the page; the groups read answers `after` once the delete went through. */
    function renderWithDelete(id: string, before: unknown[], after: unknown[]) {
      let deleted = false;
      stubApi({
        'GET /api/v1/admin/support/kb/groups': () => ({
          status: 200,
          body: deleted ? after : before,
        }),
        [`DELETE /api/v1/admin/support/kb/groups/${id}`]: () => {
          deleted = true;
          return { status: 204 };
        },
      });
      renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);
      return userEvent.setup();
    }

    it("moves focus to the next row's Delete button", async () => {
      const user = renderWithDelete('g1', [FIRST, SECOND], [SECOND]);

      await user.click(await screen.findByRole('button', { name: 'Delete Tài khoản' }));

      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Delete Credit' })).toHaveFocus(),
      );
    });

    it("moves focus to the previous row's Delete button when the last row is deleted", async () => {
      const user = renderWithDelete('g2', [FIRST, SECOND], [FIRST]);

      await user.click(await screen.findByRole('button', { name: 'Delete Credit' }));

      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Delete Tài khoản' })).toHaveFocus(),
      );
    });

    it('moves focus to the page heading when the last topic is deleted', async () => {
      const user = renderWithDelete('g1', [FIRST], []);

      await user.click(await screen.findByRole('button', { name: 'Delete Tài khoản' }));

      await waitFor(() => expect(screen.getByRole('heading', { name: 'Topics' })).toHaveFocus());
    });
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

    await user.click(await screen.findByRole('button', { name: 'Move Credit up' }));

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ ids: ['g2', 'g1'] }),
    );
    await waitFor(() =>
      expect(
        screen.getAllByRole('listitem').map((item) => item.querySelector('strong')?.textContent),
      ).toEqual(['Credit', 'Tài khoản']),
    );
    expect(screen.getByRole('button', { name: 'Move Credit up' })).toHaveFocus();
    expect(screen.getByRole('status')).toHaveTextContent('Moved Credit up to position 1');
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

    const input = await screen.findByLabelText('New name for Credit');
    await user.clear(input);
    await user.type(input, 'Thanh toán');
    await user.click(within(input.closest('li')!).getByRole('button', { name: 'Rename' }));

    expect(await screen.findByText('Thanh toán', { selector: 'strong' })).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ name: 'Thanh toán' });
    expect(screen.getByRole('status')).toHaveTextContent('Renamed to Thanh toán');
  });

  it('says why a blank rename is not sent', async () => {
    const calls = stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    const input = await screen.findByLabelText('New name for Credit');
    await user.clear(input);
    await user.type(input, '   ');
    await user.click(within(input.closest('li')!).getByRole('button', { name: 'Rename' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Topic name must be 1–60 characters');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);
  });

  it('says why a blank new group is not sent', async () => {
    const calls = stubApi({ 'GET /api/v1/admin/support/kb/groups': GROUPS });
    const user = userEvent.setup();
    renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

    await user.click(await screen.findByRole('button', { name: 'Add topic' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Topic name must be 1–60 characters');
    expect(screen.getByLabelText('New topic name')).toHaveAccessibleDescription(
      'Topic name must be 1–60 characters',
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

    await user.type(await screen.findByLabelText('New topic name'), 'Credit');
    await user.click(screen.getByRole('button', { name: 'Add topic' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A topic with this name already exists');
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

    await user.click(await screen.findByRole('button', { name: 'Move Credit up' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The topic list just changed — the latest order was reloaded',
    );
    await waitFor(() =>
      expect(
        calls.filter((call) => call.method === 'GET' && call.path.endsWith('/groups')),
      ).toHaveLength(2),
    );
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the controls and the article count message in Vietnamese', async () => {
      stubApi({
        'GET /api/v1/admin/support/kb/groups': GROUPS,
        'DELETE /api/v1/admin/support/kb/groups/g2': {
          status: 409,
          body: { status: 409, code: 'group-not-empty', articleCount: 1, detail: 'still holds 1' },
        },
      });
      const user = userEvent.setup();
      renderRoute('/support/groups', '/support/groups', <TopicGroupsPage />);

      expect(await screen.findByRole('button', { name: 'Thêm chủ đề' })).toBeInTheDocument();
      await user.click(await screen.findByRole('button', { name: 'Xoá Credit' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Nhóm còn 1 bài viết — chuyển bài sang nhóm khác trước',
      );
    });
  });
});
