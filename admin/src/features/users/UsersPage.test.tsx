import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viUsers from '../../locales/vi/users.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { UsersPage } from './UsersPage';

const ALICE = {
  id: 'u1',
  email: 'alice@flowcortex.dev',
  name: 'Alice',
  role: 'MEMBER',
  status: 'ACTIVE',
};
const BOB = {
  id: 'u2',
  email: 'bob@flowcortex.dev',
  name: 'Bob',
  role: 'MEMBER',
  status: 'ACTIVE',
};

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
    expect(
      calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/users'),
    ).toHaveLength(2);
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

  it('preserves the Creator role when editing an existing account', async () => {
    const creator = { ...ALICE, id: 'u3', email: 'creator@flowcortex.dev', role: 'CREATOR' };
    const calls = stubApi({
      'GET /api/v1/users': { status: 200, body: [creator] },
      'PUT /api/v1/users/u3': { status: 200, body: creator },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(creator.email);
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Role', { selector: '#edit-role-u3' })).toHaveValue('CREATOR');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PUT')?.body).toMatchObject({ role: 'CREATOR' }),
    );
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('shows the Vietnamese heading and the join date in vi-VN conventions', async () => {
      const createdAt = '2026-10-18T03:04:05Z';
      stubApi({ 'GET /api/v1/users': { status: 200, body: [{ ...ALICE, createdAt }] } });
      renderRoute('/users', '/users', <UsersPage />);
      await screen.findByText(ALICE.email);

      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(viUsers.title);
      const joined = screen.getByText(new Intl.DateTimeFormat('vi-VN').format(new Date(createdAt)));
      expect(joined).toHaveAttribute('dateTime', createdAt);
    });

    it('shows the role and status badges in Vietnamese', async () => {
      stubApi({ 'GET /api/v1/users': { status: 200, body: [ALICE] } });
      renderRoute('/users', '/users', <UsersPage />);
      const row = (await screen.findByText(ALICE.email)).closest('tr') as HTMLElement;

      expect(within(row).getByText(viUsers.role.MEMBER)).toHaveClass('cortex-admin-pill');
      expect(within(row).getByText(viUsers.status.ACTIVE)).toHaveClass('cortex-admin-pill');
    });
  });
});
