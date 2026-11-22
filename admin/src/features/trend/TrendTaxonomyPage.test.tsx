import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import { ADMIN_ME, renderRoute, staffMe, stubApi, type Handler } from '../../test/fakeApi';
import { TrendTaxonomyPage } from './TrendTaxonomyPage';

const ME = 'GET /api/v1/auth/me';
const LIST = 'GET /api/v1/admin/trend/taxonomy';
const CATEGORIES = '/api/v1/admin/trend/taxonomy/categories';
const TAGS = '/api/v1/admin/trend/taxonomy/tags';
const VIEW_ONLY = staffMe('OPERATION_MANAGER', ['content.trend_template.view']);

function category(key: string, nameVi: string, nameEn: string, order: number, usageCount = 0) {
  return { key, nameVi, nameEn, order, visible: true, usageCount };
}

const PEOPLE = category('nguoi', 'Người', 'People', 0);
const PRODUCTS = category('san-pham', 'Sản phẩm', 'Products', 1);
const PETS = category('thu-cung', 'Thú cưng', 'Pets', 2);
const TAXONOMY = { categories: [PEOPLE, PRODUCTS, PETS], tags: [] as unknown[] };

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-trend', true);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    },
  });
});

afterEach(async () => {
  setToken(null);
  vi.unstubAllGlobals();
  await i18n.changeLanguage('en');
  for (const [name, descriptor] of [
    ['showModal', showModal],
    ['close', close],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
  }
});

function renderPage(routes: Record<string, Handler>, me: object = ADMIN_ME) {
  const calls = stubApi({ [ME]: { status: 200, body: me }, ...routes });
  renderRoute('/content/trend-taxonomy', '/content/trend-taxonomy', <TrendTaxonomyPage />);
  return { calls, user: userEvent.setup() };
}

const rowNames = () =>
  within(screen.getByRole('table'))
    .getAllByRole('row')
    .slice(1)
    .map((row) => row.querySelector('strong')?.textContent);

describe('TrendTaxonomyPage', () => {
  it('shows a read-only page with both tabs and no write control', async () => {
    const { user } = renderPage({ [LIST]: { status: 200, body: TAXONOMY } }, VIEW_ONLY);

    expect(await screen.findByText('People')).toBeInTheDocument();
    expect(screen.getByText('You can view the taxonomy but not change it.')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Tags' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Tags' }));
    expect(screen.getByText('There are no tags.')).toBeInTheDocument();
  });

  it('adds a category, closes the dialog and returns focus to Add', async () => {
    let stored = [PEOPLE];
    const { user, calls } = renderPage({
      [LIST]: () => ({ status: 200, body: { categories: stored, tags: [] } }),
      [`POST ${CATEGORIES}`]: () => {
        stored = [...stored, category('do-an', 'Đồ ăn', 'Food', 1)];
        return { status: 201, body: stored[1] };
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = screen.getByRole('dialog', { name: 'Add category' });
    await user.type(within(dialog).getByLabelText('Vietnamese name'), 'Đồ ăn');
    await user.type(within(dialog).getByLabelText('English name'), 'Food');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      nameVi: 'Đồ ăn',
      nameEn: 'Food',
    });
    expect(rowNames()).toEqual(['Người', 'Đồ ăn']);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add category' })).toHaveFocus());
  });

  it('adds a tag from the empty Tags tab', async () => {
    let stored: unknown[] = [];
    const { user, calls } = renderPage({
      [LIST]: () => ({ status: 200, body: { categories: [], tags: stored } }),
      [`POST ${TAGS}`]: () => {
        const tag = { id: 't1', nameVi: 'Vui', nameEn: 'Fun', order: 0, visible: true, usageCount: 0 };
        stored = [tag];
        return { status: 201, body: tag };
      },
    });

    await user.click(await screen.findByRole('tab', { name: 'Tags' }));
    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    const dialog = screen.getByRole('dialog', { name: 'Add tag' });
    await user.type(within(dialog).getByLabelText('Vietnamese name'), 'Vui');
    await user.type(within(dialog).getByLabelText('English name'), 'Fun');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('button', { name: 'Delete Fun' })).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      nameVi: 'Vui',
      nameEn: 'Fun',
    });
    expect(screen.queryByText('No tags yet')).not.toBeInTheDocument();
  });

  it('checks the names before sending and focuses the first invalid field', async () => {
    const { user, calls } = renderPage({
      [LIST]: { status: 200, body: { categories: [], tags: [] } },
    });

    await user.click(await screen.findByRole('tab', { name: 'Tags' }));
    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    const dialog = screen.getByRole('dialog', { name: 'Add tag' });
    await user.type(within(dialog).getByLabelText('Vietnamese name'), 'a'.repeat(31));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    const vi = within(dialog).getByLabelText('Vietnamese name');
    expect(vi).toHaveAttribute('aria-invalid', 'true');
    expect(within(dialog).getByLabelText('English name')).toHaveAttribute('aria-invalid', 'true');
    expect(within(dialog).getAllByText('Enter 1–30 characters')).toHaveLength(2);
    expect(vi).toHaveFocus();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('shows a refused key derivation at the Vietnamese name field', async () => {
    const { user } = renderPage({
      [LIST]: { status: 200, body: TAXONOMY },
      [`POST ${CATEGORIES}`]: {
        status: 422,
        body: { status: 422, code: 'category-key-exhausted', errors: { nameVi: 'server text' } },
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Vietnamese name'), 'Người');
    await user.type(within(dialog).getByLabelText('English name'), 'People');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText(/Every key derived from this name is taken/)).toBeInTheDocument();
    expect(within(dialog).queryByText('server text')).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Vietnamese name')).toHaveFocus();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('offers hiding when a category in use refuses its delete', async () => {
    let people = { ...PEOPLE, usageCount: 2 };
    const { user, calls } = renderPage({
      [LIST]: () => ({ status: 200, body: { categories: [people, PRODUCTS], tags: [] } }),
      [`DELETE ${CATEGORIES}/nguoi`]: {
        status: 409,
        body: { status: 409, code: 'category-in-use', templateCount: 2, detail: 'in use' },
      },
      [`PATCH ${CATEGORIES}/nguoi`]: () => {
        people = { ...people, visible: false };
        return { status: 200, body: people };
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Delete People' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));

    const notice = await screen.findByText('People is used by 2 templates, so it cannot be deleted.');
    expect(notice).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hide instead' }));

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({ visible: false }),
    );
    expect(await screen.findByText('Hidden')).toBeInTheDocument();
    expect(screen.queryByText('People is used by 2 templates, so it cannot be deleted.')).not.toBeInTheDocument();
  });

  it('names how many templates lose a tag before anything is sent', async () => {
    const tag = { id: 't1', nameVi: 'Vui', nameEn: 'Fun', order: 0, visible: true, usageCount: 4 };
    const { user, calls } = renderPage({
      [LIST]: { status: 200, body: { categories: [], tags: [tag] } },
    });

    await user.click(await screen.findByRole('tab', { name: 'Tags' }));
    await user.click(await screen.findByRole('button', { name: 'Delete Fun' }));

    expect(within(screen.getByRole('dialog')).getByText('4 templates lose this tag.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);
  });

  it('sends the whole list when a row moves and announces the move', async () => {
    let stored = [PEOPLE, PRODUCTS, PETS];
    const { user, calls } = renderPage({
      [LIST]: () => ({ status: 200, body: { categories: stored, tags: [] } }),
      [`PUT ${CATEGORIES}/order`]: () => {
        stored = [
          { ...PRODUCTS, order: 0 },
          { ...PEOPLE, order: 1 },
          PETS,
        ];
        return { status: 200, body: stored };
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Move Products up' }));

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
        keys: ['san-pham', 'nguoi', 'thu-cung'],
      }),
    );
    await waitFor(() => expect(rowNames()).toEqual(['Sản phẩm', 'Người', 'Thú cưng']));
    expect(screen.getByRole('status')).toHaveTextContent('Moved Products up to position 1');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Products up' })).toHaveFocus());
  });

  it('keeps the announcement in the language the console switches to', async () => {
    let people = PEOPLE;
    const { user } = renderPage({
      [LIST]: () => ({ status: 200, body: { categories: [people, PRODUCTS], tags: [] } }),
      [`PATCH ${CATEGORIES}/nguoi`]: () => {
        people = { ...people, visible: false };
        return { status: 200, body: people };
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Hide People' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('People is now hidden'));

    await act(() => i18n.changeLanguage('vi'));

    expect(screen.getByRole('status')).toHaveTextContent('Người đã được ẩn');
  });

  it('reports a stale order, reloads the list and does not retry the move', async () => {
    const { user, calls } = renderPage({
      [LIST]: { status: 200, body: TAXONOMY },
      [`PUT ${CATEGORIES}/order`]: {
        status: 409,
        body: { status: 409, code: 'taxonomy-order-stale', detail: 'stale' },
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Move Pets up' }));

    expect(await screen.findByText(/The list changed since it was loaded/)).toBeInTheDocument();
    await waitFor(() =>
      expect(
        calls.filter((call) => call.method === 'GET' && call.path.endsWith('/taxonomy')).length,
      ).toBeGreaterThan(1),
    );
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
  });
});
