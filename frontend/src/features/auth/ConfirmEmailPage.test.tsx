import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { ConfirmEmailPage } from './ConfirmEmailPage';

function renderConfirm(search: string) {
  return renderRoute(
    '/confirm-email',
    `/confirm-email${search}`,
    <ConfirmEmailPage />,
    <Route path="/" element={<p>Home</p>} />,
  );
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
});
