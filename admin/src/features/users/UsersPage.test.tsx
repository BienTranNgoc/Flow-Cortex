import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { UsersPage } from './UsersPage';

const ALICE = { id: 'u1', email: 'alice@flowcortex.dev', name: 'Alice', role: 'MEMBER', status: 'ACTIVE' };
const BOB = { id: 'u2', email: 'bob@flowcortex.dev', name: 'Bob', role: 'MEMBER', status: 'ACTIVE' };

beforeEach(() => setToken('admin-token-users', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('UsersPage', () => {
  it('creates a user through POST /api/v1/users and refreshes the list', async () => {
    let users = [ALICE];
    const calls = stubApi({
      'GET /api/v1/users': () => ({ status: 200, body: users }),
      'POST /api/v1/users': () => {
        users = [ALICE, BOB];
        return { status: 201, body: BOB };
      },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(ALICE.email);

    await userEvent.click(screen.getByRole('button', { name: 'Add user' }));
    await userEvent.type(screen.getByLabelText('Email'), BOB.email);
    await userEvent.type(screen.getByLabelText('Password'), 'long-enough-password');
    await userEvent.type(screen.getByLabelText('Name'), BOB.name);
    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));

    expect(await screen.findByText(BOB.email)).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      email: BOB.email,
      password: 'long-enough-password',
      name: BOB.name,
      role: 'MEMBER',
    });
    expect(calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/users')).toHaveLength(2);
  });

  it('offers Retry when the list cannot be loaded', async () => {
    let failing = true;
    stubApi({
      'GET /api/v1/users': () =>
        failing ? { status: 500, body: { status: 500 } } : { status: 200, body: [ALICE] },
    });
    renderRoute('/users', '/users', <UsersPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load users.');
    failing = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(screen.getByText(ALICE.email)).toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
