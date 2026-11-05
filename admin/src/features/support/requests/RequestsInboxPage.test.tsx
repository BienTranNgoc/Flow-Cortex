import { setToken, type AdminSupportRequestSummary } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../../test/fakeApi';
import { RequestsInboxPage } from './RequestsInboxPage';

const LIST_PATH = 'GET /api/v1/admin/support/requests';

function summary(overrides: Partial<AdminSupportRequestSummary> = {}): AdminSupportRequestSummary {
  return {
    number: 12,
    type: 'credit',
    subject: 'Không nhận được credit',
    status: 'new',
    requester: { id: 'm1', email: 'member1@flowcortex.dev', deleted: false },
    createdAt: '2026-11-04T03:00:00Z',
    lastActivityAt: '2026-11-04T03:00:00Z',
    ...overrides,
  };
}

function renderInbox() {
  return renderRoute('/support/requests', '/support/requests', <RequestsInboxPage />);
}

beforeEach(() => setToken('admin-token-requests-inbox', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('RequestsInboxPage', () => {
  it('lists each request with its status, type, requester and a link to its detail', async () => {
    stubApi({
      [LIST_PATH]: {
        status: 200,
        body: {
          items: [
            summary(),
            summary({
              number: 5,
              subject: 'Xoá tài khoản',
              type: 'account',
              status: 'waiting_member',
              requester: { id: 'm9', deleted: true },
            }),
          ],
          page: 0,
          size: 20,
          total: 2,
        },
      },
    });
    renderInbox();

    const link = await screen.findByRole('link', { name: 'Không nhận được credit' });
    expect(link).toHaveAttribute('href', '/support/requests/12');
    const deletedRow = screen.getByRole('link', { name: 'Xoá tài khoản' }).closest('tr')!;
    expect(within(deletedRow).getByText('Chờ bạn phản hồi')).toBeInTheDocument();
    expect(within(deletedRow).getByText('Tài khoản')).toBeInTheDocument();
    expect(within(deletedRow).getByText('Tài khoản đã bị xoá')).toBeInTheDocument();
    expect(screen.getByText('Trang 1/1 · 2 yêu cầu')).toBeInTheDocument();
  });

  it('filters by status and type, back on the first page each time', async () => {
    const calls = stubApi({
      [LIST_PATH]: {
        status: 200,
        body: { items: [summary()], page: 0, size: 20, total: 45 },
      },
    });
    const user = userEvent.setup();
    renderInbox();

    await screen.findByRole('link', { name: 'Không nhận được credit' });
    await user.click(screen.getByRole('button', { name: 'Trang sau' }));
    await waitFor(() => expect(calls.at(-1)?.query.get('page')).toBe('1'));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Trạng thái' }), 'in_progress');
    await waitFor(() => expect(calls.at(-1)?.query.get('status')).toBe('in_progress'));
    expect(calls.at(-1)?.query.get('page')).toBe('0');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Loại' }), 'technical');
    await waitFor(() => expect(calls.at(-1)?.query.get('type')).toBe('technical'));
    expect(calls.at(-1)?.query.get('status')).toBe('in_progress');
    expect(calls.at(-1)?.query.get('page')).toBe('0');
    expect(screen.getByRole('option', { name: 'Đang xử lý' })).toBeInTheDocument();
  });

  it('says a type-only filter matched nothing and offers to clear it', async () => {
    const calls = stubApi({
      [LIST_PATH]: () => {
        const filtered = calls.at(-1)?.query.has('type') ?? false;
        return {
          status: 200,
          body: { items: filtered ? [] : [summary()], page: 0, size: 20, total: filtered ? 0 : 1 },
        };
      },
    });
    const user = userEvent.setup();
    renderInbox();

    await screen.findByRole('link', { name: 'Không nhận được credit' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Loại' }), 'other');

    expect(await screen.findByText('Không có yêu cầu nào khớp bộ lọc')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Bỏ lọc loại' }));

    expect(await screen.findByRole('link', { name: 'Không nhận được credit' })).toBeInTheDocument();
    expect(calls.at(-1)?.query.has('type')).toBe(false);
    expect(screen.getByRole('combobox', { name: 'Loại' })).toHaveFocus();
  });
});
