import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import i18n from '../../lib/i18n';
import { stubApi } from '../../test/fakeApi';
import { AuthLayout } from './AuthLayout';
import { ConfirmEmailPage } from './ConfirmEmailPage';

function renderConfirm(search: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/confirm-email${search}`]}>
        <Routes>
          <Route element={<AuthLayout />}>
            <Route path="/confirm-email" element={<ConfirmEmailPage />} />
          </Route>
          <Route path="/" element={<p>Home</p>} />
          <Route path="/set-password" element={<SetPasswordStep />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function SetPasswordStep() {
  const state = useLocation().state as { passwordSetupToken?: string } | null;
  return <p>Set password step with {state?.passwordSetupToken ?? 'no token'}</p>;
}

const LINK_INVALID = {
  status: 422,
  body: {
    status: 422,
    detail: 'This confirmation link is no longer valid',
    code: 'CONFIRMATION_LINK_INVALID',
  },
};

describe('ConfirmEmailPage', () => {
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('confirms the token once, signs the member in and lands on the home page', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/confirm-email': {
        status: 200,
        body: { accessToken: 'jwt-c', expiresIn: 900, passwordSetupRequired: false },
      },
    });

    renderConfirm('?token=link-token-1');

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-c');
    expect(calls).toEqual([
      { method: 'POST', path: '/api/v1/auth/confirm-email', body: { token: 'link-token-1' } },
    ]);
  });

  it('signs the member in and asks for a new password when confirming dropped the old one', async () => {
    stubApi({
      'POST /api/v1/auth/confirm-email': {
        status: 200,
        body: {
          accessToken: 'jwt-d',
          expiresIn: 900,
          passwordSetupRequired: true,
          passwordSetupToken: 'setup-1',
        },
      },
    });

    renderConfirm('?token=link-token-2');

    expect(await screen.findByText('Set password step with setup-1')).toBeInTheDocument();
    expect(screen.queryByText('Home')).not.toBeInTheDocument();
    expect(getToken()).toBe('jwt-d');
  });

  it('says the link is no longer valid and resends a link to the address typed', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/confirm-email': LINK_INVALID,
      'POST /api/v1/auth/resend-confirmation': {
        status: 202,
        body: { status: 'confirmation_sent' },
      },
    });
    const user = userEvent.setup();

    renderConfirm('?token=used-token');

    expect(
      await screen.findByText('This confirmation link is no longer valid'),
    ).toBeInTheDocument();
    expect(getToken()).toBeNull();
    await user.type(screen.getByLabelText('Email'), 'an@example.com');
    await user.click(screen.getByRole('button', { name: 'Resend confirmation email' }));

    expect(
      await screen.findByText('If an account needs confirming, we sent a new link'),
    ).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/resend-confirmation')).toEqual([
      {
        method: 'POST',
        path: '/api/v1/auth/resend-confirmation',
        body: { email: 'an@example.com' },
      },
    ]);
  });

  it('refuses to resend to an address without @', async () => {
    const calls = stubApi({ 'POST /api/v1/auth/confirm-email': LINK_INVALID });
    const user = userEvent.setup();
    renderConfirm('?token=used-token');

    await user.type(await screen.findByLabelText('Email'), 'an.example.com');
    await user.click(screen.getByRole('button', { name: 'Resend confirmation email' }));

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/auth/confirm-email']);
  });

  it('treats a link without a token as no longer valid and sends nothing', async () => {
    const calls = stubApi({});

    renderConfirm('');

    expect(
      await screen.findByText('This confirmation link is no longer valid'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resend confirmation email' })).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('shows the server detail and lets the member retry any other failure', async () => {
    let attempts = 0;
    stubApi({
      'POST /api/v1/auth/confirm-email': () => {
        attempts += 1;
        return attempts === 1
          ? { status: 503, body: { status: 503, detail: 'Service unavailable' } }
          : { status: 200, body: { accessToken: 'jwt-c2', expiresIn: 900 } };
      },
    });
    const user = userEvent.setup();

    renderConfirm('?token=link-token-2');

    expect(await screen.findByText('Service unavailable')).toHaveAttribute('role', 'alert');
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-c2');
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('explains an unusable link, validates the address and words a failed resend in Vietnamese', async () => {
      stubApi({
        'POST /api/v1/auth/resend-confirmation': () => {
          throw new TypeError('Failed to fetch');
        },
      });
      const user = userEvent.setup();

      renderConfirm('');

      expect(await screen.findByRole('heading', { name: 'Xác nhận email' })).toBeInTheDocument();
      expect(
        screen.getByText('Liên kết xác nhận này không còn hiệu lực').closest('[role="alert"]'),
      ).not.toBeNull();
      await user.type(screen.getByLabelText('Email'), 'an.example.com');
      await user.click(screen.getByRole('button', { name: 'Gửi lại email xác nhận' }));
      expect(await screen.findByText('Nhập một địa chỉ email hợp lệ')).toBeInTheDocument();

      await user.clear(screen.getByLabelText('Email'));
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.click(screen.getByRole('button', { name: 'Gửi lại email xác nhận' }));

      expect(
        await screen.findByText('Không gửi được liên kết mới. Vui lòng thử lại.'),
      ).toHaveAttribute('role', 'alert');
    });

    it('renders inside the auth shell with its mascot, banner stage and one level-1 heading', async () => {
      stubApi({});

      renderConfirm('');

      expect(
        await screen.findByText('Liên kết xác nhận này không còn hiệu lực'),
      ).toBeInTheDocument();
      expect(document.querySelector('.auth-card svg.nodi-peek')).not.toBeNull();
      expect(document.querySelector('.auth-shell__stage')).not.toBeNull();
      const headings = screen.getAllByRole('heading', { level: 1 });
      expect(headings).toHaveLength(1);
      expect(headings[0]).toHaveTextContent('Xác nhận email');
    });

    it('confirms the token once, signs the member in and lands on the home page', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/confirm-email': {
          status: 200,
          body: { accessToken: 'jwt-c', expiresIn: 900 },
        },
      });

      renderConfirm('?token=link-token-1');

      expect(await screen.findByText('Home')).toBeInTheDocument();
      expect(getToken()).toBe('jwt-c');
      expect(calls).toEqual([
        { method: 'POST', path: '/api/v1/auth/confirm-email', body: { token: 'link-token-1' } },
      ]);
    });

    it('says the link is no longer valid and resends a link to the address typed', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/confirm-email': LINK_INVALID,
        'POST /api/v1/auth/resend-confirmation': {
          status: 202,
          body: { status: 'confirmation_sent' },
        },
      });
      const user = userEvent.setup();

      renderConfirm('?token=used-token');

      expect(
        await screen.findByText('Liên kết xác nhận này không còn hiệu lực'),
      ).toBeInTheDocument();
      expect(getToken()).toBeNull();
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.click(screen.getByRole('button', { name: 'Gửi lại email xác nhận' }));

      expect(
        await screen.findByText('Nếu tài khoản cần xác nhận, chúng tôi đã gửi một liên kết mới'),
      ).toBeInTheDocument();
      expect(calls.filter((call) => call.path === '/api/v1/auth/resend-confirmation')).toEqual([
        {
          method: 'POST',
          path: '/api/v1/auth/resend-confirmation',
          body: { email: 'an@example.com' },
        },
      ]);
    });

    it('refuses to resend to an address without @', async () => {
      const calls = stubApi({ 'POST /api/v1/auth/confirm-email': LINK_INVALID });
      const user = userEvent.setup();
      renderConfirm('?token=used-token');

      await user.type(await screen.findByLabelText('Email'), 'an.example.com');
      await user.click(screen.getByRole('button', { name: 'Gửi lại email xác nhận' }));

      expect(await screen.findByText('Nhập một địa chỉ email hợp lệ')).toBeInTheDocument();
      expect(calls.map((call) => call.path)).toEqual(['/api/v1/auth/confirm-email']);
    });

    it('treats a link without a token as no longer valid and sends nothing', async () => {
      const calls = stubApi({});

      renderConfirm('');

      expect(
        await screen.findByText('Liên kết xác nhận này không còn hiệu lực'),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Gửi lại email xác nhận' })).toBeInTheDocument();
      expect(calls).toEqual([]);
    });

    it('worries alongside the alert when the link has no token', async () => {
      stubApi({});

      renderConfirm('');

      expect(
        await screen.findByText('Liên kết xác nhận này không còn hiệu lực'),
      ).toBeInTheDocument();
      expect(document.querySelector('.auth-card .nodi-peek')).toHaveAttribute(
        'data-pose',
        'worried',
      );
    });

    it('shows the server detail and lets the member retry any other failure', async () => {
      let attempts = 0;
      stubApi({
        'POST /api/v1/auth/confirm-email': () => {
          attempts += 1;
          return attempts === 1
            ? { status: 503, body: { status: 503, detail: 'Service unavailable' } }
            : { status: 200, body: { accessToken: 'jwt-c2', expiresIn: 900 } };
        },
      });
      const user = userEvent.setup();

      renderConfirm('?token=link-token-2');

      expect(await screen.findByText('Service unavailable')).toHaveAttribute('role', 'alert');
      await user.click(screen.getByRole('button', { name: 'Thử lại' }));

      expect(await screen.findByText('Home')).toBeInTheDocument();
      expect(getToken()).toBe('jwt-c2');
    });
  });
});
