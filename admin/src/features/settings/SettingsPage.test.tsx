import { setToken, type AdminSetting } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
});
