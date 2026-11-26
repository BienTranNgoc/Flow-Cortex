import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { RoleDetailPage } from './RoleDetailPage';

const ROLE = {
  code: 'KIEM_DUYET',
  name: 'Reviewer',
  description: null,
  system: false,
  staff: true,
  superAdmin: false,
  permissions: [],
  permissionsVersion: 1,
  accountCount: 0,
};

const CATALOG = [
  {
    key: 'support.ticket.view',
    module: 'support',
    feature: 'ticket',
    action: 'view',
    superAdminOnly: false,
  },
];

function routes(role: () => FakeReply) {
  return {
    'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
    'GET /api/v1/admin/permission-catalog': { status: 200, body: CATALOG },
    'GET /api/v1/admin/roles/KIEM_DUYET': role,
    'GET /api/v1/admin/rbac/runtime': {
      status: 200,
      body: { mutationsEnabled: true, source: 'DEFAULT', configuredValue: null, updatedBy: null, updatedAt: null },
    },
  };
}

function renderDetail() {
  return renderRoute('/roles/:code', '/roles/KIEM_DUYET', <RoleDetailPage />);
}

describe('RoleDetailPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('keeps the draft and offers a retry when a background refetch of the role fails', async () => {
    let failing = false;
    stubApi(
      routes(() => (failing ? { status: 500 } : { status: 200, body: ROLE })),
    );
    const user = userEvent.setup();
    const { queryClient } = renderDetail();

    const name = await screen.findByLabelText('Name');
    await waitFor(() => expect(name).toBeEnabled());
    await user.clear(name);
    await user.type(name, 'Senior reviewer');

    failing = true;
    await queryClient.invalidateQueries();
    await settled(queryClient);

    expect(screen.getByLabelText('Name')).toHaveValue('Senior reviewer');
    expect(await screen.findByText('Could not refresh this role. Your draft is kept.')).toBeInTheDocument();

    failing = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByLabelText('Name')).toHaveValue('Senior reviewer');
    await settled(queryClient);
    expect(screen.queryByText('Could not refresh this role. Your draft is kept.')).not.toBeInTheDocument();
  });

  it('shows the full-page error when the first load of the role fails', async () => {
    stubApi(routes(() => ({ status: 500 })));

    renderDetail();

    expect(await screen.findByText('Could not load this role.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
