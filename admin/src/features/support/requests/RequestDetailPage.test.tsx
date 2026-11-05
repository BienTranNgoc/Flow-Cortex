import { setToken, type AdminSupportRequestDetail } from '@flow-cortex/api-client';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../../test/fakeApi';
import { RequestDetailPage } from './RequestDetailPage';

const DETAIL_PATH = '/api/v1/admin/support/requests/12';
const OPEN_COUNT_KEY = ['admin', 'support', 'requests', 'open-count'];

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

function problem(status: number, code: string): FakeReply {
  return {
    status,
    body: { type: `https://flow-cortex.dev/problems/${code}`, status, code, detail: code },
  };
}

/** Answers each GET of the detail with the next body, repeating the last one. */
function sequence(...bodies: AdminSupportRequestDetail[]) {
  let calls = 0;
  const handler = () => ({ status: 200, body: bodies[Math.min(calls++, bodies.length - 1)] });
  return { handler, count: () => calls };
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
  it('offers only the statuses the server allows', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: {
        status: 200,
        body: detail({ status: 'resolved', allowedStatuses: ['in_progress', 'closed'] }),
      },
    });
    renderDetail();

    const menu = await screen.findByRole('combobox', { name: 'Đổi trạng thái' });
    const options = within(menu)
      .getAllByRole('option')
      .map((option) => option.textContent);
    expect(options).toEqual(['Chọn trạng thái mới', 'Đang xử lý', 'Đã đóng']);
  });

  it('starts the status menu on a disabled placeholder and keeps save inert until a target is chosen', async () => {
    const calls = stubApi({ [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() } });
    const user = userEvent.setup();
    renderDetail();

    const menu = await screen.findByRole('combobox', { name: 'Đổi trạng thái' });
    const save = screen.getByRole('button', { name: 'Lưu trạng thái' });
    expect(menu).toHaveValue('');
    expect(within(menu).getByRole('option', { name: 'Chọn trạng thái mới' })).toBeDisabled();
    expect(save).toHaveAttribute('aria-disabled', 'true');

    await user.click(save);
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);

    await user.selectOptions(menu, 'resolved');
    expect(save).toHaveAttribute('aria-disabled', 'false');
  });

  it('hides the status menu and the reply box once nothing is allowed', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: {
        status: 200,
        body: detail({ status: 'closed', closedReason: 'admin', allowedStatuses: [] }),
      },
    });
    renderDetail();

    expect(await screen.findByText('Quản trị viên đã đóng')).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Đổi trạng thái' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Trả lời' })).not.toBeInTheDocument();
  });

  it('names both statuses when the server refuses a transition, reading the current one again', async () => {
    const reads = sequence(
      detail(),
      detail({ status: 'closed', closedReason: 'cancelled', allowedStatuses: [] }),
    );
    const calls = stubApi({
      [`GET ${DETAIL_PATH}`]: reads.handler,
      [`PUT ${DETAIL_PATH}/status`]: problem(409, 'transition-not-allowed'),
    });
    const user = userEvent.setup();
    renderDetail();

    await user.selectOptions(await screen.findByRole('combobox', { name: 'Đổi trạng thái' }), 'in_progress');
    await user.click(screen.getByRole('button', { name: 'Lưu trạng thái' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển Đã đóng → Đang xử lý');
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ status: 'in_progress' });
  });

  it('offers a reload when another actor changed the request first', async () => {
    const reads = sequence(
      detail(),
      detail({ status: 'closed', closedReason: 'admin', allowedStatuses: [] }),
    );
    stubApi({
      [`GET ${DETAIL_PATH}`]: reads.handler,
      [`PUT ${DETAIL_PATH}/status`]: problem(409, 'request-state-changed'),
    });
    const user = userEvent.setup();
    renderDetail();

    await user.selectOptions(await screen.findByRole('combobox', { name: 'Đổi trạng thái' }), 'resolved');
    await user.click(screen.getByRole('button', { name: 'Lưu trạng thái' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Yêu cầu vừa được cập nhật — tải lại để xem',
    );
    await user.click(screen.getByRole('button', { name: 'Tải lại' }));
    expect(await screen.findByText('Đã đóng', { selector: '.status-badge' })).toBeInTheDocument();
  });

  it('after a lost race marks the open count stale and keeps focus once the menu closes', async () => {
    const reads = sequence(
      detail(),
      detail({ status: 'closed', closedReason: 'admin', allowedStatuses: [] }),
    );
    stubApi({
      [`GET ${DETAIL_PATH}`]: reads.handler,
      [`PUT ${DETAIL_PATH}/status`]: problem(409, 'request-state-changed'),
    });
    const user = userEvent.setup();
    const { queryClient } = renderDetail();
    queryClient.setQueryData(OPEN_COUNT_KEY, { count: 3 });

    await user.selectOptions(await screen.findByRole('combobox', { name: 'Đổi trạng thái' }), 'in_progress');
    await user.click(screen.getByRole('button', { name: 'Lưu trạng thái' }));
    await screen.findByRole('alert');
    expect(queryClient.getQueryState(OPEN_COUNT_KEY)?.isInvalidated).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Tải lại' }));
    await screen.findByText('Đã đóng', { selector: '.status-badge' });
    await waitFor(() => expect(document.activeElement).toHaveClass('request-feedback'));
  });

  it('renders assistant answers as Markdown and member questions as plain text', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: {
        status: 200,
        body: detail({
          conversation: [
            { role: 'user', text: 'Chữ **đậm** ở đây?', createdAt: '2026-11-04T02:50:00Z' },
            {
              role: 'assistant',
              text: 'Vào **Cài đặt** → Credit.',
              outcome: 'answered',
              createdAt: '2026-11-04T02:50:05Z',
            },
          ],
        }),
      },
    });
    renderDetail();

    expect(await screen.findByText('Cài đặt', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText('Chữ **đậm** ở đây?')).toBeInTheDocument();
  });

  it('says images cannot be sent while the upload grant is switched off', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() },
      'POST /api/v1/support/uploads': problem(503, 'support-disabled'),
    });
    const user = userEvent.setup();
    renderDetail();

    await user.type(await screen.findByRole('textbox', { name: 'Trả lời' }), 'Ảnh đính kèm đây.');
    await user.upload(
      screen.getByLabelText('Đính kèm ảnh (tối đa 3)'),
      new File(['png'], 'a.png', { type: 'image/png' }),
    );
    await user.click(screen.getByRole('button', { name: 'Gửi trả lời' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Tạm thời chưa gửi được ảnh — bỏ ảnh để gửi trả lời không kèm ảnh',
    );
  });

  it('keeps the chosen reply status while a reply is being sent', async () => {
    let release: (reply: FakeReply) => void = () => undefined;
    const calls = stubApi({
      [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() },
      [`POST ${DETAIL_PATH}/messages`]: () =>
        new Promise<FakeReply>((resolve) => {
          release = resolve;
        }),
    });
    const user = userEvent.setup();
    renderDetail();

    await user.type(await screen.findByRole('textbox', { name: 'Trả lời' }), 'Đang kiểm tra.');
    await user.click(screen.getByRole('button', { name: 'Gửi trả lời' }));
    await screen.findByRole('button', { name: 'Đang gửi…' });
    const resolved = screen.getByRole('radio', { name: 'Đã giải quyết' });
    expect(resolved).toHaveAttribute('aria-disabled', 'true');
    await user.click(resolved);

    expect(resolved).not.toBeChecked();
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({
      status: 'waiting_member',
    });
    release({ status: 201, body: detail({ status: 'waiting_member' }) });
    expect(await screen.findByText('Chờ bạn phản hồi', { selector: '.status-badge' })).toBeInTheDocument();
  });

  it('shows the linked conversation, then replies with "Đã giải quyết" and refreshes the open count', async () => {
    const replied = detail({
      status: 'resolved',
      allowedStatuses: ['in_progress', 'closed'],
      messages: [
        ...detail().messages,
        {
          id: 'msg2',
          authorKind: 'admin',
          body: 'Đã cộng 100 credit.\nBạn kiểm tra lại nhé.',
          createdAt: '2026-11-05T03:00:00Z',
          attachments: [],
        },
      ],
    });
    const calls = stubApi({
      [`GET ${DETAIL_PATH}`]: { status: 200, body: detail() },
      [`POST ${DETAIL_PATH}/messages`]: { status: 201, body: replied },
    });
    const user = userEvent.setup();
    const { queryClient } = renderDetail();
    queryClient.setQueryData(OPEN_COUNT_KEY, { count: 3 });

    expect(await screen.findByText('Nạp credit thế nào?')).toBeInTheDocument();
    expect(screen.getByText('Vẫn chưa thấy credit?')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Trả lời' }), 'Đã cộng 100 credit.');
    await user.click(screen.getByRole('radio', { name: 'Đã giải quyết' }));
    await user.click(screen.getByRole('button', { name: 'Gửi trả lời' }));

    expect(await screen.findByText('Đã giải quyết', { selector: '.status-badge' })).toBeInTheDocument();
    expect(screen.getByText(/Bạn kiểm tra lại nhé/)).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      body: 'Đã cộng 100 credit.',
      uploadIds: [],
      status: 'resolved',
    });
    const [headers] = sentHeaders(`${DETAIL_PATH}/messages`, 'POST');
    expect(headers?.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(queryClient.getQueryState(OPEN_COUNT_KEY)?.isInvalidated).toBe(true);
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

    await user.type(await screen.findByRole('textbox', { name: 'Trả lời' }), 'Bạn gửi thêm ảnh nhé.');
    await user.click(screen.getByRole('button', { name: 'Gửi trả lời' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa gửi được trả lời. Bạn thử lại nhé.');
    await user.click(screen.getByRole('button', { name: 'Gửi trả lời' }));

    expect(await screen.findByText('Chờ bạn phản hồi', { selector: '.status-badge' })).toBeInTheDocument();
    const keys = sentHeaders(`${DETAIL_PATH}/messages`, 'POST').map((h) => h.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('flags a deleted requester and disables the reply box, still allowing Đã đóng', async () => {
    stubApi({
      [`GET ${DETAIL_PATH}`]: {
        status: 200,
        body: detail({ requester: { id: 'm9', deleted: true } }),
      },
    });
    renderDetail();

    expect(await screen.findByText('Tài khoản đã bị xoá')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Trả lời' })).toBeDisabled();
    expect(screen.getByText('Không thể trả lời vì tài khoản người gửi đã bị xoá.')).toBeInTheDocument();
    const menu = screen.getByRole('combobox', { name: 'Đổi trạng thái' });
    expect(within(menu).getByRole('option', { name: 'Đã đóng' })).toBeInTheDocument();
  });

  it('says the linked conversation expired after 90 days', async () => {
    const expired: Partial<AdminSupportRequestDetail> = detail({ conversationExpired: true });
    delete expired.conversation;
    stubApi({ [`GET ${DETAIL_PATH}`]: { status: 200, body: expired } });
    renderDetail();

    expect(await screen.findByText('Hội thoại đã hết hạn lưu (90 ngày)')).toBeInTheDocument();
  });

  it('reads the request again for fresh links when an attachment fails to load', async () => {
    const withImage = detail({
      messages: [
        {
          ...detail().messages[0]!,
          attachments: [
            {
              position: 1,
              contentType: 'image/png',
              sizeBytes: 1200,
              url: 'https://media.example/a.png?sig=old',
              expiresAt: '2026-11-04T03:10:00Z',
            },
          ],
        },
      ],
    });
    const reads = sequence(withImage);
    stubApi({ [`GET ${DETAIL_PATH}`]: reads.handler });
    renderDetail();

    fireEvent.error(await screen.findByRole('img', { name: 'Ảnh đính kèm 1' }));

    await waitFor(() => expect(reads.count()).toBe(2));
  });

  it('says the request does not exist on 404', async () => {
    stubApi({ [`GET ${DETAIL_PATH}`]: problem(404, 'request-not-found') });
    renderDetail();

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy yêu cầu này.');
  });
});
