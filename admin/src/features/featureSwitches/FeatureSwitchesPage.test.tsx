import { setToken, type AdminFeature, type AdminSetting } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { FeatureSwitchesPage } from './FeatureSwitchesPage';

const ME = '/api/v1/auth/me';
const FEATURES = '/api/v1/admin/features';
const SETTINGS = '/api/v1/admin/settings';
const RESET_KEY = 'user.pw_reset.enabled';
const CANCEL_KEY = 'workflow.run_cancellation.enabled';

const FEATURE_LIST: AdminFeature[] = [
  { name: 'emailConfirmation', settingKey: null, value: true },
  { name: 'runCancellation', settingKey: CANCEL_KEY, value: false },
  { name: 'passwordReset', settingKey: RESET_KEY, value: true },
  { name: 'chat', settingKey: null, value: false },
];

function switchSetting(key: string, group: string, overrides: Partial<AdminSetting> = {}): AdminSetting {
  return {
    key,
    group,
    type: 'SWITCH',
    value: true,
    decidedValue: true,
    source: 'DEFAULT',
    storedValue: null,
    configuredValue: null,
    defaultValue: true,
    options: [],
    min: null,
    max: null,
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

const RESET = switchSetting(RESET_KEY, 'user');
const CANCEL = switchSetting(CANCEL_KEY, 'workflow', {
  value: false,
  decidedValue: false,
  source: 'VETOED',
  storedValue: true,
  configuredValue: false,
});

function renderPage() {
  return renderRoute('/features', '/features', <FeatureSwitchesPage />);
}

beforeEach(() => setToken('admin-token-features', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('FeatureSwitchesPage', () => {
  it('shows a runtime switch as a toggle, an environment-only one read-only and a vetoed one with its note', async () => {
    stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${FEATURES}`]: { status: 200, body: { items: FEATURE_LIST } },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [RESET, CANCEL] } },
    });
    renderPage();

    expect(await screen.findByRole('switch', { name: 'Password reset' })).toBeChecked();

    const email = screen.getByText('Email confirmation').closest('li')!;
    expect(within(email).getByText('Email confirmation')).toBeInTheDocument();
    expect(within(email).getByText('On')).toBeInTheDocument();
    expect(within(email).getByText('Changes need a server restart')).toBeInTheDocument();
    expect(within(email).queryByRole('switch')).not.toBeInTheDocument();

    const cancel = screen.getByRole('switch', { name: 'Run cancellation' }).closest('li')!;
    expect(within(cancel).getByRole('switch', { name: 'Run cancellation' })).not.toBeChecked();
    expect(within(cancel).getByText('Turned off by the server configuration')).toBeInTheDocument();
  });

  it('turns a runtime switch off through the settings API', async () => {
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${FEATURES}`]: { status: 200, body: { items: FEATURE_LIST } },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [RESET, CANCEL] } },
      [`PUT ${SETTINGS}/${RESET_KEY}`]: {
        status: 200,
        body: {
          ...RESET,
          value: false,
          decidedValue: false,
          source: 'STORED',
          storedValue: false,
          updatedBy: 'admin@flowcortex.dev',
          updatedAt: '2026-11-20T01:00:00Z',
        },
      },
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('switch', { name: 'Password reset' }));

    expect(await screen.findByText('Set by an admin')).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ value: false });
    expect(screen.getByRole('switch', { name: 'Password reset' })).not.toBeChecked();
  });

  it('is read-only without the update key', async () => {
    stubApi({
      [`GET ${ME}`]: { status: 200, body: staffMe('CUSTOMER_SUPPORT', ['platform.setting.view']) },
      [`GET ${FEATURES}`]: { status: 200, body: { items: FEATURE_LIST } },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [RESET, CANCEL] } },
    });
    renderPage();

    expect(await screen.findByText('You can view these settings but not change them.')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Password reset' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Reset to configured value' })).not.toBeInTheDocument();
  });

  it('offers a retry when the list cannot be loaded', async () => {
    let fail = true;
    stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${FEATURES}`]: () =>
        fail ? { status: 500, body: { status: 500 } } : { status: 200, body: { items: FEATURE_LIST } },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [RESET, CANCEL] } },
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('Could not load the feature switches.')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByRole('switch', { name: 'Password reset' })).toBeInTheDocument();
  });

  it('shows a runtime switch read-only with its value when its setting cannot be loaded', async () => {
    stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${FEATURES}`]: { status: 200, body: { items: FEATURE_LIST } },
      [`GET ${SETTINGS}`]: { status: 500, body: { status: 500 } },
    });
    renderPage();

    expect(await screen.findByText(/^Could not refresh the latest feature switches/)).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    const reset = screen.getByText('Password reset').closest('li')!;
    expect(within(reset).getByText('On')).toBeInTheDocument();
    expect(within(reset).queryByText('Changes need a server restart')).not.toBeInTheDocument();
  });

});
