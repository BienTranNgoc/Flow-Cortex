import { setToken, type AdminSupportRequestSummary } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../../lib/i18n';
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
    expect(within(deletedRow).getByText('Waiting for member')).toBeInTheDocument();
    expect(within(deletedRow).getByText('Account')).toBeInTheDocument();
    expect(within(deletedRow).getByText('Account deleted')).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 1 · 2 requests')).toBeInTheDocument();
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
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(calls.at(-1)?.query.get('page')).toBe('1'));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'in_progress');
    await waitFor(() => expect(calls.at(-1)?.query.get('status')).toBe('in_progress'));
    expect(calls.at(-1)?.query.get('page')).toBe('0');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Type' }), 'technical');
    await waitFor(() => expect(calls.at(-1)?.query.get('type')).toBe('technical'));
    expect(calls.at(-1)?.query.get('status')).toBe('in_progress');
    expect(calls.at(-1)?.query.get('page')).toBe('0');
    expect(screen.getByRole('option', { name: 'In progress' })).toBeInTheDocument();
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
    await user.selectOptions(screen.getByRole('combobox', { name: 'Type' }), 'other');

    expect(await screen.findByText('No requests match this filter')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear type filter' }));

    expect(await screen.findByRole('link', { name: 'Không nhận được credit' })).toBeInTheDocument();
    expect(calls.at(-1)?.query.has('type')).toBe(false);
    expect(screen.getByRole('combobox', { name: 'Type' })).toHaveFocus();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the filters, columns, statuses and dates in Vietnamese', async () => {
      stubApi({
        [LIST_PATH]: {
          status: 200,
          body: {
            items: [summary({ status: 'waiting_member', requester: { id: 'm9', deleted: true } })],
            page: 0,
            size: 20,
            total: 1,
          },
        },
      });
      renderInbox();

      const row = (await screen.findByRole('link', { name: 'Không nhận được credit' })).closest(
        'tr',
      )!;
      expect(within(row).getByText('Chờ bạn phản hồi')).toBeInTheDocument();
      expect(within(row).getByText('Tài khoản đã bị xoá')).toBeInTheDocument();
      expect(within(row).getByText('10:00 04/11/2026')).toBeInTheDocument();
      expect(screen.getByRole('combobox', { name: 'Loại' })).toBeInTheDocument();
      expect(screen.getByText('Trang 1/1 · 1 yêu cầu')).toBeInTheDocument();
    });
  });
});
