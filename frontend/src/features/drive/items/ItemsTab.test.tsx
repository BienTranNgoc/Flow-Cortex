import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled } from '../../../test/fakeApi';
import { stubModalDialog } from '../../../test/modalDialog';
import { ItemsTab } from './ItemsTab';

const EMPTY_TITLE = 'Nothing saved here yet';
const SIZE = 20;

function textItem(index: number) {
  return {
    id: `item-${index}`,
    name: `Item ${index}`,
    contentType: 'text',
    semanticRole: 'caption',
    source: 'manual',
    textValue: `Text ${index}`,
    createdAt: '2026-11-20T08:00:00Z',
    updatedAt: '2026-11-20T08:00:00Z',
  };
}

describe('ItemsTab', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    setToken('t', true);
    restoreDialog = stubModalDialog();
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('steps back to the previous page without showing the empty state when a delete empties the last page', async () => {
    const items = Array.from({ length: SIZE + 1 }, (_, index) => textItem(index + 1));
    let releaseFirstPage: () => void = () => {};
    let holdFirstPage: Promise<void> | null = null;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string, init?: RequestInit) => {
        const method = init?.method ?? 'GET';
        const [path = '', query = ''] = input.split('?');
        if (method === 'GET' && path === '/api/v1/drive/suggestions') return json(200, []);
        if (method === 'GET' && path === '/api/v1/drive/items') {
          const page = Number(new URLSearchParams(query).get('page'));
          if (page === 0) await holdFirstPage;
          return json(200, { items: items.slice(page * SIZE, (page + 1) * SIZE), total: items.length, page, size: SIZE });
        }
        if (method === 'DELETE' && path.startsWith('/api/v1/drive/items/')) {
          items.pop();
          return new Response(null, { status: 204 });
        }
        return json(404, { status: 404, detail: 'Not found' });
      }),
    );
    const user = userEvent.setup();
    const { queryClient } = renderRoute(
      '/drive',
      '/drive',
      <ItemsTab kept={new Set()} onKept={() => {}} />,
    );
    await settled(queryClient);

    await user.click(screen.getByRole('button', { name: 'Page 2' }));
    await screen.findByRole('heading', { name: 'Item 21' });

    // The first page is gone from the cache, so the step-back has to wait for the network.
    queryClient.removeQueries({ queryKey: ['drive', 'items', 'text', 0] });
    holdFirstPage = new Promise<void>((resolve) => {
      releaseFirstPage = resolve;
    });
    const emptyShown = vi.fn();
    const observer = new MutationObserver(() => {
      if (screen.queryByText(EMPTY_TITLE)) emptyShown();
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });

    await user.click(screen.getByRole('button', { name: 'Delete Item 21' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_TITLE)).not.toBeInTheDocument();

    releaseFirstPage();
    expect(await screen.findByRole('heading', { name: 'Item 1' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument());
    observer.disconnect();

    expect(emptyShown).not.toHaveBeenCalled();
    expect(screen.queryByText(EMPTY_TITLE)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Item 20' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Item 21' })).not.toBeInTheDocument();
  });
});

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}
