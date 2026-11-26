import { setToken, type AdminSetting } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { stubDialogElement } from '../../test/dialog';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { SettingsPage } from './SettingsPage';

const LIST = '/api/v1/admin/settings';
const ME = '/api/v1/auth/me';
const QUOTA = 'support.assistant.member_daily_quota';
const SWITCH = 'support.assistant.enabled';

const MEMBER_QUOTA: AdminSetting = {
  key: QUOTA,
  group: 'support',
  type: 'INTEGER',
  value: 20,
  decidedValue: 20,
  source: 'CONFIGURED',
  storedValue: null,
  configuredValue: 20,
  defaultValue: null,
  options: [],
  min: 1,
  max: 2147483647,
  updatedBy: null,
  updatedAt: null,
};

const ASSISTANT: AdminSetting = {
  key: SWITCH,
  group: 'support',
  type: 'SWITCH',
  value: false,
  decidedValue: true,
  source: 'VETOED',
  storedValue: true,
  configuredValue: false,
  defaultValue: true,
  options: [],
  min: null,
  max: null,
  updatedBy: 'admin@nodi.vn',
  updatedAt: '2026-10-19T06:00:00Z',
};

function renderSettings() {
  return renderRoute('/settings', '/settings', <SettingsPage />);
}

beforeEach(() => setToken('admin-token-settings', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('SettingsPage', () => {
  it('lists keys by group with source, bounds and last change, and explains a veto', async () => {
    stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${LIST}`]: { status: 200, body: { items: [ASSISTANT, MEMBER_QUOTA] } },
    });
    renderSettings();

    expect(await screen.findByRole('heading', { name: 'Help Center' })).toBeInTheDocument();
    const quota = screen.getByRole('textbox', { name: 'Questions per member per day' });
    expect(quota).toHaveValue('20');
    expect(screen.getByText('From server configuration')).toBeInTheDocument();
    expect(screen.getByText('From 1 to 2147483647')).toBeInTheDocument();
    expect(screen.getByText('Changed by admin@nodi.vn at 10/19/2026, 13:00')).toBeInTheDocument();
    expect(screen.getByText(/^Turned off by the server configuration;/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'AI assistant' })).toBeChecked();
  });

  it('refuses an out-of-bounds entry without calling the server, then saves a valid one', async () => {
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${LIST}`]: { status: 200, body: { items: [MEMBER_QUOTA] } },
      [`PUT ${LIST}/${QUOTA}`]: {
        status: 200,
        body: {
          ...MEMBER_QUOTA,
          value: 30,
          decidedValue: 30,
          source: 'STORED',
          storedValue: 30,
          updatedBy: 'admin@flowcortex.dev',
          updatedAt: '2026-10-20T01:00:00Z',
        },
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const quota = await screen.findByRole('textbox', { name: 'Questions per member per day' });
    await user.clear(quota);
    await user.type(quota, '0');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter a whole number from 1 to 2147483647.',
    );
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);

    await user.clear(quota);
    await user.type(quota, '30');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Set by an admin')).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ value: 30 });
    expect(screen.getByRole('button', { name: 'Reset to configured value' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the message the server wrote for a refused value', async () => {
    stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${LIST}`]: { status: 200, body: { items: [MEMBER_QUOTA] } },
      [`PUT ${LIST}/${QUOTA}`]: {
        status: 422,
        body: { status: 422, code: 'setting.invalidValue', errors: { value: 'Not allowed here.' } },
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const quota = await screen.findByRole('textbox', { name: 'Questions per member per day' });
    await user.clear(quota);
    await user.type(quota, '5');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not allowed here.');
  });

  it('resets a stored value and shows the configured one again', async () => {
    const stored: AdminSetting = {
      ...MEMBER_QUOTA,
      value: 30,
      decidedValue: 30,
      source: 'STORED',
      storedValue: 30,
    };
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${LIST}`]: { status: 200, body: { items: [stored] } },
      [`DELETE ${LIST}/${QUOTA}`]: { status: 200, body: MEMBER_QUOTA },
    });
    const user = userEvent.setup();
    renderSettings();

    await user.click(await screen.findByRole('button', { name: 'Reset to configured value' }));

    expect(await screen.findByText('From server configuration')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Questions per member per day' })).toHaveValue('20');
    expect(calls.some((call) => call.method === 'DELETE')).toBe(true);
    expect(
      screen.queryByRole('button', { name: 'Reset to configured value' }),
    ).not.toBeInTheDocument();
  });

  it('is read-only without the update key', async () => {
    const stored: AdminSetting = { ...MEMBER_QUOTA, source: 'STORED', storedValue: 20 };
    stubApi({
      [`GET ${ME}`]: {
        status: 200,
        body: staffMe('CUSTOMER_SUPPORT', ['platform.setting.view']),
      },
      [`GET ${LIST}`]: { status: 200, body: { items: [ASSISTANT, stored] } },
    });
    renderSettings();

    expect(await screen.findByText('You can view these settings but not change them.')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'AI assistant' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Questions per member per day' })).toHaveAttribute(
      'readonly',
    );
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reset to configured value' })).not.toBeInTheDocument();
  });

  it('loads a key history only when asked, newest first, with a deleted account named as such', async () => {
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${LIST}`]: { status: 200, body: { items: [ASSISTANT] } },
      [`GET ${LIST}/${SWITCH}/history`]: {
        status: 200,
        body: {
          items: [
            { previousValue: false, newValue: true, changedBy: null, changedAt: '2026-10-19T06:00:00Z' },
            { previousValue: null, newValue: false, changedBy: 'a@nodi.vn', changedAt: '2026-10-18T06:00:00Z' },
          ],
          page: 0,
          size: 10,
          total: 2,
        },
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const history = await screen.findByRole('button', { name: 'Show history' });
    expect(calls.some((call) => call.path.endsWith('/history'))).toBe(false);
    await user.click(history);

    const newest = (await screen.findByText('Off → On')).closest('li');
    const oldest = screen.getByText('not set → Off').closest('li');
    expect(newest).toHaveTextContent('Deleted account');
    expect(oldest).toHaveTextContent('a@nodi.vn');
    expect(newest?.compareDocumentPosition(oldest as Node)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.getByText('1–2 of 2')).toBeInTheDocument();
    expect(calls.find((call) => call.path.endsWith('/history'))?.query.get('size')).toBe('10');
  });

  describe('lowering the versions kept per project', () => {
    stubDialogElement();

    const VERSIONS = 'media.editor.project_max_versions';
    const VERSIONS_LABEL = 'Versions kept per project';
    const VERSIONS_ITEM: AdminSetting = {
      key: VERSIONS,
      group: 'media',
      type: 'INTEGER',
      value: 50,
      decidedValue: 50,
      source: 'CONFIGURED',
      storedValue: null,
      configuredValue: 50,
      defaultValue: null,
      options: [],
      min: 1,
      max: 200,
      updatedBy: null,
      updatedAt: null,
    };
    const stored = (value: number): AdminSetting => ({
      ...VERSIONS_ITEM,
      value,
      decidedValue: value,
      source: 'STORED',
      storedValue: value,
      updatedBy: 'admin@flowcortex.dev',
      updatedAt: '2026-10-20T01:00:00Z',
    });

    function stubVersions(item: AdminSetting) {
      return stubApi({
        [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
        [`GET ${LIST}`]: { status: 200, body: { items: [item] } },
        [`PUT ${LIST}/${VERSIONS}`]: { status: 200, body: stored(20) },
        [`DELETE ${LIST}/${VERSIONS}`]: { status: 200, body: VERSIONS_ITEM },
      });
    }

    const sent = (calls: ReturnType<typeof stubApi>, method: string) =>
      calls.filter((call) => call.method === method);

    async function typeValue(user: ReturnType<typeof userEvent.setup>, value: string) {
      const field = await screen.findByRole('textbox', { name: VERSIONS_LABEL });
      await user.clear(field);
      await user.type(field, value);
      return field;
    }

    it('holds a lowering behind a dialog naming both values and sends nothing until confirmed', async () => {
      const calls = stubVersions(VERSIONS_ITEM);
      const user = userEvent.setup();
      renderSettings();

      await typeValue(user, '20');
      await user.click(screen.getByRole('button', { name: 'Save' }));

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('Lowering from 50 to 20');
      expect(sent(calls, 'PUT')).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: 'Lower and save' }));

      expect(await screen.findByText('Set by an admin')).toBeInTheDocument();
      expect(sent(calls, 'PUT')).toHaveLength(1);
      expect(sent(calls, 'PUT')[0]?.body).toEqual({ value: 20 });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    });

    it('cancel sends nothing, keeps the draft and returns focus to Save', async () => {
      const calls = stubVersions(VERSIONS_ITEM);
      const user = userEvent.setup();
      renderSettings();

      const field = await typeValue(user, '20');
      await user.click(screen.getByRole('button', { name: 'Save' }));
      await user.click(await screen.findByRole('button', { name: 'Cancel' }));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(sent(calls, 'PUT')).toHaveLength(0);
      expect(field).toHaveValue('20');
      expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    });

    it('closing the dialog with Escape sends nothing and returns focus to Save', async () => {
      const calls = stubVersions(VERSIONS_ITEM);
      const user = userEvent.setup();
      renderSettings();

      await typeValue(user, '20');
      await user.click(screen.getByRole('button', { name: 'Save' }));
      const dialog = await screen.findByRole('dialog');
      act(() => {
        dialog.dispatchEvent(new Event('close'));
      });

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(sent(calls, 'PUT')).toHaveLength(0);
      expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    });

    it('raising the value goes out at once without a dialog', async () => {
      const calls = stubVersions(VERSIONS_ITEM);
      const user = userEvent.setup();
      renderSettings();

      await typeValue(user, '80');
      await user.click(screen.getByRole('button', { name: 'Save' }));

      await waitFor(() => expect(sent(calls, 'PUT')).toHaveLength(1));
      expect(sent(calls, 'PUT')[0]?.body).toEqual({ value: 80 });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('resetting a stored value below it asks first and deletes only after confirm', async () => {
      const calls = stubVersions(stored(80));
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: 'Reset to configured value' }));

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('Lowering from 80 to 50');
      expect(sent(calls, 'DELETE')).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: 'Lower and save' }));

      expect(await screen.findByText('From server configuration')).toBeInTheDocument();
      expect(sent(calls, 'DELETE')).toHaveLength(1);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('cancelling a lowering reset deletes nothing and returns focus to the reset button', async () => {
      const calls = stubVersions(stored(80));
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: 'Reset to configured value' }));
      await user.click(await screen.findByRole('button', { name: 'Cancel' }));

      expect(sent(calls, 'DELETE')).toHaveLength(0);
      expect(screen.getByRole('button', { name: 'Reset to configured value' })).toHaveFocus();
    });
  });
});
