import { setToken, type AdminSupportRequestFacets } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../../test/fakeApi';
import { RequestsInboxPage } from './RequestsInboxPage';

const LIST = '/api/v1/admin/support/requests';
const FACETS = '/api/v1/admin/support/requests/facets';

function facets(newCount: number): AdminSupportRequestFacets {
  return {
    status: [{ value: 'new', count: newCount }],
    type: [],
    created: [],
    assignee: { unassigned: newCount, mine: 0, people: [] },
  };
}

describe('RequestsInboxPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('re-reads the filter counts together with the list on Refresh', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const calls = stubApi({
      [`GET ${LIST}`]: { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
      [`GET ${FACETS}`]: () => {
        reads += 1;
        return { status: 200, body: facets(reads === 1 ? 3 : 4) };
      },
    });
    const count = (path: string) => calls.filter((call) => call.path === path).length;
    renderRoute('/support/requests', '/support/requests', <RequestsInboxPage />);
    expect(await screen.findByRole('option', { name: 'New (3)' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(await screen.findByRole('option', { name: 'New (4)' })).toBeInTheDocument();
    await waitFor(() => expect(count(LIST)).toBe(2));
    expect(count(FACETS)).toBe(2);
  });
});
