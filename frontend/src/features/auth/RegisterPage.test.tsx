import { fireEvent, screen } from '@testing-library/react';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { RegisterPage } from './RegisterPage';

function submitRegistration() {
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'New Creator' } });
  fireEvent.change(screen.getByLabelText('Work or personal email'), {
    target: { value: 'new.creator@example.com' },
  });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'register-password-1' } });
  fireEvent.change(screen.getByLabelText('Confirm password'), {
    target: { value: 'register-password-1' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Get started free →' }));
}

describe('RegisterPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each<[string, FakeReply]>([
    [
      'registration is closed (403)',
      { status: 403, body: { status: 403, detail: 'Self-registration is not available' } },
    ],
    [
      'the attempt is rate-limited (429)',
      {
        status: 429,
        body: { status: 429, detail: 'Too many registration attempts; try again later' },
      },
    ],
  ])('shows the server detail when %s and stays on the form', async (_case, reply) => {
    const calls = stubApi({ 'POST /api/v1/auth/register': reply });
    renderRoute('/register', '/register', <RegisterPage />);

    submitRegistration();

    const detail = (reply.body as { detail: string }).detail;
    expect(await screen.findByText(detail)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('form', { name: 'Create account' })).toBeInTheDocument();
    expect(calls).toEqual([
      {
        method: 'POST',
        path: '/api/v1/auth/register',
        body: {
          name: 'New Creator',
          email: 'new.creator@example.com',
          password: 'register-password-1',
        },
      },
    ]);
  });
});
