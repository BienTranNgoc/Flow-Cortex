import { setToken, type AdminSupportSettings } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../../lib/i18n';
import { renderRoute, settled, stubApi } from '../../../test/fakeApi';
import { SupportSettingsPage } from './SupportSettingsPage';

const SETTINGS_PATH = '/api/v1/admin/support/settings';

const SETTINGS: AdminSupportSettings = {
  assistantEnabled: true,
  updatedBy: 'admin@nodi.vn',
  updatedAt: '2026-10-19T06:00:00Z',
  envEnabled: true,
  ceilingReachedToday: false,
  dailyCeiling: 2000,
};

function settings(overrides: Partial<AdminSupportSettings> = {}): AdminSupportSettings {
  return { ...SETTINGS, ...overrides };
}

function renderSettings() {
  return renderRoute('/support/settings', '/support/settings', <SupportSettingsPage />);
}

beforeEach(() => setToken('admin-token-support-settings', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('SupportSettingsPage', () => {
  it('switches the assistant off and shows who switched it and when', async () => {
    const calls = stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() },
      [`PUT ${SETTINGS_PATH}`]: {
        status: 200,
        body: settings({ assistantEnabled: false, updatedAt: '2026-10-19T07:05:00Z' }),
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const toggle = await screen.findByRole('switch', { name: 'AI assistant' });
    expect(toggle).toBeChecked();
    expect(screen.getByText('Turned on by admin@nodi.vn at 10/19/2026, 13:00')).toBeInTheDocument();
    await user.click(toggle);

    expect(
      await screen.findByText('Turned off by admin@nodi.vn at 10/19/2026, 14:05'),
    ).toBeInTheDocument();
    expect(toggle).not.toBeChecked();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ assistantEnabled: false });
  });

  it('says the server configuration keeps the assistant off', async () => {
    stubApi({ [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings({ envEnabled: false }) } });
    renderSettings();

    expect(await screen.findByText('Turned off by the server configuration')).toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: 'AI assistant' });
    expect(toggle).toBeChecked();
    expect(toggle).toHaveAccessibleDescription(/Turned off by the server configuration/);
  });

  it('keeps focus on the switch and sends one change while the first is saving', async () => {
    const calls = stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() },
      [`PUT ${SETTINGS_PATH}`]: () => new Promise(() => {}),
    });
    const user = userEvent.setup();
    renderSettings();

    const toggle = await screen.findByRole('switch', { name: 'AI assistant' });
    await user.click(toggle);
    await user.click(toggle);

    expect(toggle).toHaveFocus();
    expect(toggle).toHaveAttribute('aria-disabled', 'true');
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
  });

  it("says the assistant is paused once today's ceiling is reached", async () => {
    stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings({ ceilingReachedToday: true, dailyCeiling: 23 }) },
    });
    renderSettings();

    expect(
      await screen.findByText("Paused: today's limit of 23 questions was reached"),
    ).toBeInTheDocument();
  });

  it('shows only the time when the admin who switched it is gone', async () => {
    // A since-deleted admin: the server leaves the field out.
    const withoutAuthor: Partial<AdminSupportSettings> = { ...SETTINGS };
    delete withoutAuthor.updatedBy;
    stubApi({ [`GET ${SETTINGS_PATH}`]: { status: 200, body: withoutAuthor } });
    const { queryClient } = renderSettings();

    expect(await screen.findByText('Turned on at 10/19/2026, 13:00')).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByText(/ by /)).not.toBeInTheDocument();
    expect(screen.queryByText('Turned off by the server configuration')).not.toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the switch and the last change in Vietnamese', async () => {
      stubApi({
        [`GET ${SETTINGS_PATH}`]: {
          status: 200,
          body: settings({ envEnabled: false, ceilingReachedToday: true, dailyCeiling: 23 }),
        },
      });
      renderSettings();

      expect(await screen.findByRole('switch', { name: 'Trợ lý AI' })).toBeChecked();
      expect(screen.getByText('Bật bởi admin@nodi.vn lúc 13:00 19/10/2026')).toBeInTheDocument();
      expect(screen.getByText('Đang tắt bởi cấu hình máy chủ')).toBeInTheDocument();
      expect(screen.getByText('Tạm dừng: đã đạt giới hạn 23 câu hôm nay')).toBeInTheDocument();
    });
  });
});
