import { setToken, type SupportRequestDetail } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { stubApi, type FakeReply, type Handler } from '../../test/fakeApi';
import { FakeXhr } from '../../test/fakeXhr';
import { SupportWidget } from './SupportWidget';

const REQUESTS = '/api/v1/support/requests';

class AutoXhr extends FakeXhr {
  override send(body: unknown) {
    super.send(body);
    queueMicrotask(() => this.respond(200));
  }
}

function detail(overrides: Partial<SupportRequestDetail> = {}): SupportRequestDetail {
  return {
    number: 12,
    type: 'credit',
    subject: 'Không thấy credit',
    status: 'waiting_member',
    memberUnread: false,
    createdAt: '2026-10-19T08:00:00Z',
    lastActivityAt: '2026-10-19T09:00:00Z',
    messages: [
      {
        id: 'm1',
        authorKind: 'member',
        body: 'Tôi nạp credit nhưng số dư không đổi.',
        createdAt: '2026-10-19T08:00:00Z',
        attachments: [],
      },
      {
        id: 'm2',
        authorKind: 'admin',
        body: 'Bạn thử đăng xuất rồi đăng nhập lại nhé.',
        createdAt: '2026-10-19T09:00:00Z',
        attachments: [],
      },
    ],
    ...overrides,
  };
}

function api(routes: Record<string, Handler>, contactEmail?: string): Record<string, Handler> {
  return {
    'GET /api/v1/support/settings': {
      status: 200,
      body: contactEmail
        ? { contactEmail, assistantAvailable: true }
        : { assistantAvailable: true },
    },
    [`GET ${REQUESTS}`]: { status: 200, body: [] },
    ...routes,
  };
}

function problem(status: number, code: string): FakeReply {
  return { status, body: { status, code, type: `https://flow-cortex.dev/problems/${code}` } };
}

function renderWidget(url: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="*" element={<p>Trang</p>} />
        </Routes>
        <SupportWidget />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return {
    user: userEvent.setup(),
    panel: () => screen.getByRole('dialog', { name: 'Trung tâm trợ giúp' }),
  };
}

/** Opens the panel on Yêu cầu through a `?help=` link. */
async function openRequests(target = 'requests') {
  const rendered = renderWidget(`/workflows?help=${target}`);
  await screen.findByRole('dialog', { name: 'Trung tâm trợ giúp' });
  return rendered;
}

async function fillForm(user: ReturnType<typeof userEvent.setup>, subject: string) {
  const panel = screen.getByRole('dialog');
  await user.selectOptions(within(panel).getByLabelText('Loại yêu cầu'), 'Credit');
  await user.type(within(panel).getByLabelText('Tiêu đề'), subject);
  await user.type(
    within(panel).getByLabelText('Mô tả'),
    'Tôi nạp 100 credit nhưng số dư vẫn bằng 0.',
  );
}

function idempotencyKeys(path: string): (string | null)[] {
  return vi
    .mocked(fetch)
    .mock.calls.filter(([url, init]) => url === path && init?.method === 'POST')
    .map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'));
}

beforeEach(() => {
  setToken('member', true);
  FakeXhr.instances.length = 0;
  vi.stubGlobal('XMLHttpRequest', AutoXhr);
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview') });
});

afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('RequestsTab', () => {
  it('files a request with a PNG and shows #13 in "Mới" with the description and the image', async () => {
    const created = detail({
      number: 13,
      status: 'new',
      messages: [
        {
          id: 'm1',
          authorKind: 'member',
          body: 'Tôi nạp 100 credit nhưng số dư vẫn bằng 0.',
          createdAt: '2026-10-19T08:00:00Z',
          attachments: [
            {
              position: 1,
              contentType: 'image/png',
              sizeBytes: 819_200,
              url: 'http://minio.test/get/13-1',
              expiresAt: '2026-10-19T09:00:00Z',
            },
          ],
        },
      ],
    });
    const calls = stubApi(
      api({
        'POST /api/v1/support/uploads': {
          status: 201,
          body: {
            uploadId: 'u1',
            url: 'http://minio.test/put/u1',
            method: 'PUT',
            headers: { 'Content-Type': 'image/png' },
            expiresAt: '2026-10-19T09:00:00Z',
            maxBytes: 5_242_880,
          },
        },
        [`POST ${REQUESTS}`]: { status: 201, body: created },
        [`GET ${REQUESTS}/13`]: { status: 200, body: created },
      }),
    );
    const { user, panel } = await openRequests();

    await user.click(await within(panel()).findByRole('button', { name: 'Gửi yêu cầu hỗ trợ' }));
    await fillForm(user, 'Không thấy credit');
    const png = new File([new Uint8Array(819_200)], 'man-hinh.png', { type: 'image/png' });
    await user.upload(within(panel()).getByLabelText('Đính kèm ảnh (tối đa 3)'), png);
    expect(within(panel()).getByRole('img', { name: 'man-hinh.png' })).toBeInTheDocument();
    await user.click(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));

    expect(
      await within(panel()).findByRole('heading', { name: 'Yêu cầu #13' }),
    ).toBeInTheDocument();
    expect(await within(panel()).findByText('Mới')).toBeInTheDocument();
    expect(
      within(panel()).getByText('Tôi nạp 100 credit nhưng số dư vẫn bằng 0.'),
    ).toBeInTheDocument();
    expect(within(panel()).getByRole('img', { name: 'Ảnh đính kèm 1' })).toHaveAttribute(
      'src',
      'http://minio.test/get/13-1',
    );
    expect(FakeXhr.instances.map((xhr) => [xhr.method, xhr.url, xhr.body])).toEqual([
      ['PUT', 'http://minio.test/put/u1', png],
    ]);
    expect(calls.find((call) => call.method === 'POST' && call.path === REQUESTS)?.body).toEqual({
      type: 'credit',
      subject: 'Không thấy credit',
      description: 'Tôi nạp 100 credit nhưng số dư vẫn bằng 0.',
      uploadIds: ['u1'],
    });
  });

  it('opens an empty form from the list, unlinked to any conversation', async () => {
    const calls = stubApi(
      api({
        [`POST ${REQUESTS}`]: { status: 201, body: detail({ number: 13, status: 'new' }) },
        [`GET ${REQUESTS}/13`]: { status: 200, body: detail({ number: 13, status: 'new' }) },
      }),
    );
    const { user, panel } = await openRequests();

    await user.click(await within(panel()).findByRole('button', { name: 'Gửi yêu cầu hỗ trợ' }));

    expect(within(panel()).getByLabelText('Mô tả')).toHaveValue('');
    await fillForm(user, 'Không thấy credit');
    await user.click(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true));
    expect(calls.find((call) => call.method === 'POST')?.body).not.toHaveProperty(
      'aiConversationId',
    );
  });

  it('sends nothing for a 3-character subject and says why', async () => {
    const calls = stubApi(api({}));
    const { user, panel } = await openRequests();

    await user.click(await within(panel()).findByRole('button', { name: 'Gửi yêu cầu hỗ trợ' }));
    await fillForm(user, 'abc');
    await user.click(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));

    expect(within(panel()).getByText('Tiêu đề cần 5–120 ký tự')).toBeInTheDocument();
    expect(within(panel()).getByLabelText('Tiêu đề')).toHaveAttribute('aria-invalid', 'true');
    expect(calls.filter((call) => call.method === 'POST')).toEqual([]);
  });

  it('says "Không tìm thấy yêu cầu" for a number that is not the member\'s', async () => {
    stubApi(api({ [`GET ${REQUESTS}/14`]: problem(404, 'request-not-found') }));
    const { panel } = await openRequests('request-14');

    expect(within(panel()).getByRole('heading', { name: 'Yêu cầu #14' })).toBeInTheDocument();
    expect(await within(panel()).findByText('Không tìm thấy yêu cầu')).toBeInTheDocument();
  });

  it('adds a reply to a request waiting for the member, which becomes "Đang xử lý"', async () => {
    const waiting = detail();
    const answered = detail({
      status: 'in_progress',
      messages: [
        ...waiting.messages,
        {
          id: 'm3',
          authorKind: 'member',
          body: 'Đã thử, vẫn lỗi',
          createdAt: '2026-10-19T10:00:00Z',
          attachments: [],
        },
      ],
    });
    let current = waiting;
    const calls = stubApi(
      api({
        [`GET ${REQUESTS}/12`]: () => ({ status: 200, body: current }),
        [`POST ${REQUESTS}/12/messages`]: () => {
          current = answered;
          return { status: 201, body: answered };
        },
      }),
    );
    const { user, panel } = await openRequests('request-12');

    expect(await within(panel()).findByText('Chờ bạn phản hồi')).toBeInTheDocument();
    expect(within(panel()).getByText('Nodi hỗ trợ')).toBeInTheDocument();
    await user.type(within(panel()).getByLabelText('Trả lời'), 'Đã thử, vẫn lỗi');
    await user.click(within(panel()).getByRole('button', { name: 'Gửi trả lời' }));

    expect(await within(panel()).findByText('Đang xử lý')).toBeInTheDocument();
    expect(within(panel()).getByText('Đã thử, vẫn lỗi')).toBeInTheDocument();
    expect(within(panel()).getByLabelText('Trả lời')).toHaveValue('');
    expect(within(panel()).getByLabelText('Trả lời')).toHaveFocus();
    expect(calls.find((call) => call.path === `${REQUESTS}/12/messages`)?.body).toEqual({
      body: 'Đã thử, vẫn lỗi',
      uploadIds: [],
    });
  });

  it('replaces the reply box of a closed request with "Yêu cầu đã đóng" and "Tạo yêu cầu mới"', async () => {
    stubApi(
      api({
        [`GET ${REQUESTS}/9`]: {
          status: 200,
          body: detail({ number: 9, status: 'closed', closedReason: 'admin' }),
        },
      }),
    );
    const { user, panel } = await openRequests('request-9');

    expect(await within(panel()).findByText('Yêu cầu đã đóng')).toBeInTheDocument();
    expect(within(panel()).queryByLabelText('Trả lời')).toBeNull();
    expect(within(panel()).queryByRole('button', { name: 'Huỷ yêu cầu' })).toBeNull();
    await user.click(within(panel()).getByRole('button', { name: 'Tạo yêu cầu mới' }));

    expect(within(panel()).getByRole('heading', { name: 'Yêu cầu mới' })).toBeInTheDocument();
  });

  it('closes a resolved request on "Đã xong, đóng yêu cầu" and removes the reply box', async () => {
    const calls = stubApi(
      api({
        [`GET ${REQUESTS}/12`]: { status: 200, body: detail({ status: 'resolved' }) },
        [`POST ${REQUESTS}/12/confirm`]: {
          status: 200,
          body: detail({ status: 'closed', closedReason: 'confirmed' }),
        },
      }),
    );
    const { user, panel } = await openRequests('request-12');

    expect(await within(panel()).findByText('Đã giải quyết')).toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: 'Huỷ yêu cầu' })).toBeNull();
    await user.click(within(panel()).getByRole('button', { name: 'Đã xong, đóng yêu cầu' }));

    expect(await within(panel()).findByText('Đã đóng')).toBeInTheDocument();
    expect(within(panel()).queryByLabelText('Trả lời')).toBeNull();
    expect(calls.filter((call) => call.path === `${REQUESTS}/12/confirm`)).toHaveLength(1);
    await waitFor(() => expect(panel()).toContainElement(document.activeElement as HTMLElement));
  });

  it('asks before cancelling, and "Không" keeps the request open', async () => {
    const calls = stubApi(
      api({
        [`GET ${REQUESTS}/12`]: { status: 200, body: detail({ status: 'in_progress' }) },
        [`POST ${REQUESTS}/12/cancel`]: {
          status: 200,
          body: detail({ status: 'closed', closedReason: 'cancelled' }),
        },
      }),
    );
    const { user, panel } = await openRequests('request-12');
    const cancels = () => calls.filter((call) => call.path === `${REQUESTS}/12/cancel`);

    await user.click(await within(panel()).findByRole('button', { name: 'Huỷ yêu cầu' }));
    expect(cancels()).toEqual([]);
    await user.click(within(panel()).getByRole('button', { name: 'Không' }));
    expect(cancels()).toEqual([]);
    expect(within(panel()).getByRole('button', { name: 'Huỷ yêu cầu' })).toHaveFocus();

    await user.click(within(panel()).getByRole('button', { name: 'Huỷ yêu cầu' }));
    await user.click(within(panel()).getByRole('button', { name: 'Có, huỷ yêu cầu' }));

    expect(await within(panel()).findByText('Bạn đã huỷ yêu cầu')).toBeInTheDocument();
    expect(cancels()).toHaveLength(1);
    await waitFor(() => expect(panel()).toContainElement(document.activeElement as HTMLElement));
  });

  it('makes a UUID v4 idempotency key where randomUUID is missing (insecure context)', async () => {
    const real = globalThis.crypto;
    vi.stubGlobal('crypto', { getRandomValues: real.getRandomValues.bind(real) });
    stubApi(
      api({
        [`POST ${REQUESTS}`]: { status: 201, body: detail({ number: 13, status: 'new' }) },
        [`GET ${REQUESTS}/13`]: { status: 200, body: detail({ number: 13, status: 'new' }) },
      }),
    );
    const { user, panel } = await openRequests();
    await user.click(await within(panel()).findByRole('button', { name: 'Gửi yêu cầu hỗ trợ' }));
    await fillForm(user, 'Không thấy credit');

    await user.click(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));

    await waitFor(() => expect(idempotencyKeys(REQUESTS)).toHaveLength(1));
    expect(idempotencyKeys(REQUESTS)[0]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it('offers a reload when the request changed under the member (409 request-state-changed)', async () => {
    const calls = stubApi(
      api({
        [`GET ${REQUESTS}/12`]: { status: 200, body: detail({ status: 'in_progress' }) },
        [`POST ${REQUESTS}/12/cancel`]: problem(409, 'request-state-changed'),
      }),
    );
    const { user, panel } = await openRequests('request-12');
    const reads = () => calls.filter((call) => call.path === `${REQUESTS}/12`).length;

    await user.click(await within(panel()).findByRole('button', { name: 'Huỷ yêu cầu' }));
    await user.click(within(panel()).getByRole('button', { name: 'Có, huỷ yêu cầu' }));

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'Yêu cầu vừa được cập nhật — tải lại để xem',
    );
    const before = reads();
    await user.click(within(panel()).getByRole('button', { name: 'Tải lại' }));
    await waitFor(() => expect(reads()).toBe(before + 1));
  });

  it('sends a double-pressed request once, and a retry of the same submit with the same key', async () => {
    let release: (reply: FakeReply) => void = () => {};
    let attempts = 0;
    stubApi(
      api({
        [`POST ${REQUESTS}`]: () => {
          attempts += 1;
          if (attempts === 1) return new Promise<FakeReply>((resolve) => (release = resolve));
          return { status: 201, body: detail({ number: 13, status: 'new' }) };
        },
        [`GET ${REQUESTS}/13`]: { status: 200, body: detail({ number: 13, status: 'new' }) },
      }),
    );
    const { user, panel } = await openRequests();
    await user.click(await within(panel()).findByRole('button', { name: 'Gửi yêu cầu hỗ trợ' }));
    await fillForm(user, 'Không thấy credit');

    await user.dblClick(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));
    await waitFor(() => expect(idempotencyKeys(REQUESTS)).toHaveLength(1));
    release({ status: 500, body: { status: 500 } });
    expect(await within(panel()).findByRole('alert')).toHaveTextContent('Chưa gửi được');
    await user.click(within(panel()).getByRole('button', { name: 'Gửi yêu cầu' }));

    expect(
      await within(panel()).findByRole('heading', { name: 'Yêu cầu #13' }),
    ).toBeInTheDocument();
    const keys = idempotencyKeys(REQUESTS);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(keys[1]).toBe(keys[0]);
  });

  it('reads the request again once when an attachment image fails, without looping', async () => {
    const attachment = {
      position: 1,
      contentType: 'image/png',
      sizeBytes: 1000,
      url: 'http://minio.test/get/expired',
      expiresAt: '2026-10-19T09:00:00Z',
    };
    const message = { ...detail().messages[0]!, attachments: [attachment] };
    const calls = stubApi(
      api({ [`GET ${REQUESTS}/12`]: { status: 200, body: detail({ messages: [message] }) } }),
    );
    const { panel } = await openRequests('request-12');
    const image = await within(panel()).findByRole('img', { name: 'Ảnh đính kèm 1' });
    const reads = () => calls.filter((call) => call.path === `${REQUESTS}/12`).length;
    await waitFor(() => expect(reads()).toBe(1));

    fireEvent.error(image);
    await waitFor(() => expect(reads()).toBe(2));
    fireEvent.error(within(panel()).getByRole('img', { name: 'Ảnh đính kèm 1' }));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(reads()).toBe(2);
  });

  it('marks the help button while a reply is unread and clears it once the request is opened', async () => {
    let unread = 1;
    stubApi(
      api({
        'GET /api/v1/features': { status: 200, body: { supportWidget: true } },
        [`GET ${REQUESTS}/unread-count`]: () => ({ status: 200, body: { count: unread } }),
        [`GET ${REQUESTS}`]: {
          status: 200,
          body: [
            {
              number: 12,
              type: 'credit',
              subject: 'Không thấy credit',
              status: 'waiting_member',
              memberUnread: true,
              createdAt: '2026-10-19T08:00:00Z',
              lastActivityAt: '2026-10-19T09:00:00Z',
            },
          ],
        },
        [`GET ${REQUESTS}/12`]: () => {
          unread = 0;
          return { status: 200, body: detail() };
        },
      }),
    );
    const { user, panel } = renderWidget('/workflows');

    const launcher = await screen.findByRole('button', { name: 'Trợ giúp. Có phản hồi mới' });
    await user.click(launcher);
    await user.click(within(panel()).getByRole('tab', { name: 'Yêu cầu. Có phản hồi mới' }));
    await user.click(await within(panel()).findByRole('button', { name: /Không thấy credit/ }));

    expect(await screen.findByRole('button', { name: 'Trợ giúp' })).toBeInTheDocument();
  });

  it.each([
    ['with the contact line when one is configured', 'hotro@nodi.vn'],
    ['without a contact line when none is configured', undefined],
  ])(
    'reads "Tài khoản của bạn đang bị tạm khoá" for a suspended member, %s',
    async (_case, email) => {
      const calls = stubApi(
        api({ [`GET ${REQUESTS}`]: { status: 403, body: { status: 403 } } }, email),
      );
      const { panel } = await openRequests();

      const notice = await within(panel()).findByText('Tài khoản của bạn đang bị tạm khoá');
      if (email) {
        const contact = await within(panel()).findByRole('link', {
          name: `Gửi email tới ${email}`,
        });
        expect(contact).toHaveAttribute('href', `mailto:${email}`);
        expect(
          notice.compareDocumentPosition(contact) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
      } else {
        await waitFor(() =>
          expect(calls.some((call) => call.path === '/api/v1/support/settings')).toBe(true),
        );
        await new Promise((resolve) => setTimeout(resolve, 20));
        expect(within(panel()).queryByText(/Cần người hỗ trợ/)).toBeNull();
      }
      expect(within(panel()).queryByRole('button', { name: 'Gửi yêu cầu hỗ trợ' })).toBeNull();
    },
  );
});
