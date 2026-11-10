import { setToken, type AdminSupportRequestDetail } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../../test/fakeApi';
import { RequestDetailPage } from './RequestDetailPage';

const DETAIL_PATH = '/api/v1/admin/support/requests/12';

function detail(overrides: Partial<AdminSupportRequestDetail> = {}): AdminSupportRequestDetail {
  return {
    number: 12,
    type: 'credit',
    subject: 'Không nhận được credit',
    status: 'new',
    memberUnread: false,
    createdAt: '2026-11-04T03:00:00Z',
    lastActivityAt: '2026-11-04T03:00:00Z',
    requester: { id: 'm1', email: 'member1@flowcortex.dev', deleted: false },
    messages: [
      {
        id: 'msg1',
        authorKind: 'member',
        body: 'Tôi nạp 100 credit nhưng số dư vẫn bằng 0.',
        createdAt: '2026-11-04T03:00:00Z',
        attachments: [],
      },
    ],
    conversation: [
      { role: 'user', text: 'Nạp credit thế nào?', createdAt: '2026-11-04T02:50:00Z' },
      {
        role: 'assistant',
        text: 'Vào Cài đặt → Credit.',
        outcome: 'answered',
        createdAt: '2026-11-04T02:50:05Z',
      },
      { role: 'user', text: 'Vẫn chưa thấy credit?', createdAt: '2026-11-04T02:55:00Z' },
      {
        role: 'assistant',
        text: 'Mình chưa có thông tin về việc này.',
        outcome: 'no_answer',
        createdAt: '2026-11-04T02:55:05Z',
      },
    ],
    conversationExpired: false,
    allowedStatuses: ['in_progress', 'resolved', 'closed'],
    ...overrides,
  };
}

function renderDetail() {
  return renderRoute('/support/requests/:number', '/support/requests/12', <RequestDetailPage />);
}

function sentHeaders(path: string, method: string): Headers[] {
  return vi
    .mocked(fetch)
    .mock.calls.filter(([url, init]) => url === path && init?.method === method)
    .map(([, init]) => new Headers(init?.headers));
}

beforeEach(() => setToken('admin-token-requests', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('RequestDetailPage', () => {
  it('counts a trailing no-break space in a reply as the server does', async () => {
    const calls = stubApi({ [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() } });
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('textbox', { name: 'Reply' }));
    await user.paste('a'.repeat(5000) + ' ');
    await user.click(screen.getByRole('button', { name: 'Send reply' }));

    expect(await screen.findByText('Reply must be 1–5000 characters')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('retries a failed reply with the same idempotency key', async () => {
    let posts = 0;
    stubApi({
      [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() },
      [`POST ${DETAIL_PATH}/messages`]: () =>
        ++posts === 1 ? { status: 500 } : { status: 201, body: detail({ status: 'waiting_member' }) },
    });
    const user = userEvent.setup();
    renderDetail();

    await user.type(await screen.findByRole('textbox', { name: 'Reply' }), 'Bạn gửi thêm ảnh nhé.');
    await user.click(screen.getByRole('button', { name: 'Send reply' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not send the reply. Please try again.');
    await user.click(screen.getByRole('button', { name: 'Send reply' }));

    expect(await screen.findByText('Waiting for member', { selector: '.status-badge' })).toBeInTheDocument();
    const keys = sentHeaders(`${DETAIL_PATH}/messages`, 'POST').map((h) => h.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });
});
