import { setToken, type AiModel } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viProviders from '../../locales/vi/providers.json';
import { ADMIN_ME, renderRoute, staffMe, stubApi, type Handler } from '../../test/fakeApi';
import { ProvidersPage } from './ProvidersPage';

const ME = 'GET /api/v1/auth/me';
const MODELS = 'GET /api/v1/admin/ai/models';
const OVERVIEW = 'GET /api/v1/admin/ai/providers';
const VENDOR = 'GET /api/v1/admin/ai/providers/gemini/models';
const VIEW_ONLY = staffMe('OPERATION_MANAGER', ['ai.provider.view']);

function model(overrides: Partial<AiModel> = {}): AiModel {
  return {
    modelKey: 'gemini-3.8-flash',
    providerKey: 'gemini',
    displayName: 'Gemini 3.8 Flash',
    maxOutputTokens: 4096,
    supportsVision: false,
    supportsAudio: false,
    active: true,
    displayOrder: 0,
    createdAt: '2026-11-10T00:00:00Z',
    updatedAt: '2026-11-10T00:00:00Z',
    ...overrides,
  };
}

const page = (items: AiModel[]) => ({ items, page: 0, size: 50, total: items.length });

const OVERVIEW_BODY = {
  categories: [
    {
      category: 'text',
      providers: [
        {
          key: 'gemini',
          configured: true,
          missingSettings: [],
          liveCheckable: true,
          slots: [
            {
              name: 'dub-translate',
              models: [
                { modelKey: 'gemini-3.5-flash-lite', status: 'unregistered' },
                { modelKey: 'gemini-3.8-flash', status: 'inactive' },
              ],
            },
            {
              name: 'dub-speech',
              models: [{ modelKey: 'gemini-3.8-flash-tts', status: 'not_applicable' }],
            },
          ],
        },
      ],
    },
  ],
};

function renderPage(routes: Record<string, Handler>, me = ADMIN_ME) {
  const calls = stubApi({
    [ME]: { status: 200, body: me },
    [OVERVIEW]: { status: 200, body: OVERVIEW_BODY },
    ...routes,
  });
  renderRoute('/providers', '/providers', <ProvidersPage />);
  return calls;
}

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-providers', true);
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
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  for (const [name, descriptor] of [
    ['showModal', showModal],
    ['close', close],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
  }
});

describe('ProvidersPage', () => {
  it('adds a model picked from the vendor list and shows the new row', async () => {
    let rows: AiModel[] = [];
    const calls = renderPage({
      [MODELS]: () => ({ status: 200, body: page(rows) }),
      [VENDOR]: {
        status: 200,
        body: { models: [{ id: 'gemini-3.8-flash', displayName: 'Gemini 3.8 Flash' }] },
      },
      'POST /api/v1/admin/ai/models': () => {
        rows = [model()];
        return { status: 201, body: model() };
      },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Add model' }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(dialog.querySelector('datalist option')).toHaveAttribute('value', 'gemini-3.8-flash'),
    );
    await user.type(within(dialog).getByLabelText('Model id'), 'gemini-3.8-flash');
    expect(within(dialog).getByLabelText('Display name')).toHaveValue('Gemini 3.8 Flash');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('table')).toHaveTextContent('gemini-3.8-flash');
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({
      modelKey: 'gemini-3.8-flash',
      providerKey: 'gemini',
      displayName: 'Gemini 3.8 Flash',
      maxOutputTokens: 4096,
      active: true,
    });
  });

  it('says the vendor list could not be loaded and still accepts a typed id', async () => {
    const calls = renderPage({
      [MODELS]: { status: 200, body: page([]) },
      [VENDOR]: { status: 200, body: { models: [], failure: 'rejected' } },
      'POST /api/v1/admin/ai/models': { status: 201, body: model({ modelKey: 'typed-id' }) },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Add model' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      "The provider's model list could not be loaded (rejected)",
    );
    await user.type(within(dialog).getByLabelText('Model id'), 'typed-id');
    await user.type(within(dialog).getByLabelText('Display name'), 'Typed');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true));
  });

  it('puts the server field errors next to their fields', async () => {
    renderPage({
      [MODELS]: { status: 200, body: page([]) },
      [VENDOR]: { status: 200, body: { models: [] } },
      'POST /api/v1/admin/ai/models': {
        status: 400,
        body: {
          status: 400,
          code: 'llm-model-invalid',
          detail: 'The model entry is not valid',
          errors: { modelKey: 'The provider does not accept this model id' },
        },
      },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Add model' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Model id'), 'gpt-5');
    await user.type(within(dialog).getByLabelText('Display name'), 'GPT');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    const field = within(dialog).getByLabelText('Model id');
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'));
    expect(field).toHaveAccessibleDescription('The provider does not accept this model id');
  });

  it('shows both tabs read-only to a holder of the view key only', async () => {
    renderPage({ [MODELS]: { status: 200, body: page([model()]) } }, VIEW_ONLY);
    const user = userEvent.setup();

    expect(await screen.findByRole('table')).toHaveTextContent('Gemini 3.8 Flash');
    expect(screen.queryByRole('button', { name: 'Add model' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(Edit|Delete|Test)/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Providers' }));
    expect(await screen.findByRole('article', { name: 'gemini' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Test/ })).not.toBeInTheDocument();
  });

  it('shows the translated failure and latency on the row after a model test', async () => {
    renderPage({
      [MODELS]: { status: 200, body: page([model()]) },
      'POST /api/v1/admin/ai/models/gemini-3.8-flash/test': {
        status: 200,
        body: {
          ok: false,
          tested: true,
          code: 'quota_exceeded',
          latencyMs: 412,
          missingSettings: [],
        },
      },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Test Gemini 3.8 Flash' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      "The provider's quota is exhausted. (412 ms)",
    );
  });

  it('shows the Vietnamese failure text for the same result', async () => {
    await i18n.changeLanguage('vi');
    try {
      renderPage({
        [MODELS]: { status: 200, body: page([model()]) },
        'POST /api/v1/admin/ai/models/gemini-3.8-flash/test': {
          status: 200,
          body: {
            ok: false,
            tested: true,
            code: 'quota_exceeded',
            latencyMs: 412,
            missingSettings: [],
          },
        },
      });
      const user = userEvent.setup();

      await user.click(
        await screen.findByRole('button', {
          name: viProviders.models.testFor.replace('{{name}}', 'Gemini 3.8 Flash'),
        }),
      );

      expect(await screen.findByRole('status')).toHaveTextContent(
        viProviders.probe.code.quota_exceeded,
      );
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('flips the active flag with a full replace and deletes after a confirmation', async () => {
    const calls = renderPage({
      [MODELS]: { status: 200, body: page([model()]) },
      'PUT /api/v1/admin/ai/models/gemini-3.8-flash': {
        status: 200,
        body: model({ active: false }),
      },
      'DELETE /api/v1/admin/ai/models/gemini-3.8-flash': { status: 204 },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('switch', { name: 'Active: Gemini 3.8 Flash' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    expect(calls.find((call) => call.method === 'PUT')?.body).toMatchObject({
      modelKey: 'gemini-3.8-flash',
      active: false,
      maxOutputTokens: 4096,
    });

    await user.click(screen.getByRole('button', { name: 'Delete Gemini 3.8 Flash' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete model' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE')).toBe(true));
  });

  it('lists every slot model with its registry state and tests a provider', async () => {
    renderPage({
      [MODELS]: { status: 200, body: page([]) },
      'POST /api/v1/admin/ai/providers/gemini/test': {
        status: 200,
        body: { ok: true, tested: true, latencyMs: 90, missingSettings: [] },
      },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('tab', { name: 'Providers' }));
    const card = await screen.findByRole('article', { name: 'gemini' });
    expect(card).toHaveTextContent('Not registered');
    expect(card).toHaveTextContent('Inactive');
    expect(within(card).getByText('gemini-3.8-flash-tts').parentElement).toHaveTextContent(
      'Not applicable',
    );
    await user.click(within(card).getByRole('button', { name: 'Test gemini' }));

    expect(await within(card).findByRole('status')).toHaveTextContent('Reachable in 90 ms');
  });
});
