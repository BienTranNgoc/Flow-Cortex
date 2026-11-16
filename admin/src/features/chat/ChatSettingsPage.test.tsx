import { setToken, type AdminChatSettings } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { ChatSettingsPage } from './ChatSettingsPage';

const SETTINGS_PATH = '/api/v1/admin/chat/settings';

const SETTINGS: AdminChatSettings = {
  enabled: true,
  updatedBy: 'admin@nodi.vn',
  updatedAt: '2026-10-19T06:00:00Z',
};

function settings(overrides: Partial<AdminChatSettings> = {}): AdminChatSettings {
  return { ...SETTINGS, ...overrides };
}

function renderSettings() {
  return renderRoute('/chat/settings', '/chat/settings', <ChatSettingsPage />);
}

beforeEach(() => setToken('admin-token-chat-settings', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('ChatSettingsPage', () => {
  it('switches chat off and shows who switched it and when', async () => {
    const calls = stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() },
      [`PUT ${SETTINGS_PATH}`]: {
        status: 200,
        body: settings({ enabled: false, updatedAt: '2026-10-19T07:05:00Z' }),
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const toggle = await screen.findByRole('switch', { name: 'Chat' });
    expect(toggle).toBeChecked();
    expect(
      screen.getByText('Turned on by admin@nodi.vn at 10/19/2026, 13:00'),
    ).toBeInTheDocument();
    await user.click(toggle);

    expect(
      await screen.findByText('Turned off by admin@nodi.vn at 10/19/2026, 14:05'),
    ).toBeInTheDocument();
    expect(toggle).not.toBeChecked();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ enabled: false });
  });

  it('keeps focus on the switch and sends one change while the first is saving', async () => {
    const calls = stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() },
      [`PUT ${SETTINGS_PATH}`]: () => new Promise(() => {}),
    });
    const user = userEvent.setup();
    renderSettings();

    const toggle = await screen.findByRole('switch', { name: 'Chat' });
    await user.click(toggle);
    await user.click(toggle);

    expect(toggle).toHaveFocus();
    expect(toggle).toHaveAttribute('aria-disabled', 'true');
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
  });

  it('shows only the time when the author is not recorded', async () => {
    const withoutAuthor: Partial<AdminChatSettings> = { ...SETTINGS };
    delete withoutAuthor.updatedBy;
    stubApi({ [`GET ${SETTINGS_PATH}`]: { status: 200, body: withoutAuthor } });
    const { queryClient } = renderSettings();

    expect(await screen.findByText('Turned on at 10/19/2026, 13:00')).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByText(/ by /)).not.toBeInTheDocument();
  });

  it('shows no last-change line when the switch was never touched', async () => {
    stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: { enabled: true } satisfies AdminChatSettings },
    });
    const { queryClient } = renderSettings();

    await screen.findByRole('switch', { name: 'Chat' });
    await settled(queryClient);
    expect(screen.queryByText(/Turned o/)).not.toBeInTheDocument();
  });

  it('says so when saving fails', async () => {
    stubApi({
      [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() },
      [`PUT ${SETTINGS_PATH}`]: { status: 400, body: { title: 'Bad request', status: 400 } },
    });
    const user = userEvent.setup();
    renderSettings();

    await user.click(await screen.findByRole('switch', { name: 'Chat' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save. Please try again.');
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the switch and the last change in Vietnamese', async () => {
      stubApi({ [`GET ${SETTINGS_PATH}`]: { status: 200, body: settings() } });
      renderSettings();

      expect(await screen.findByRole('switch', { name: 'Trò chuyện' })).toBeChecked();
      expect(screen.getByText('Bật bởi admin@nodi.vn lúc 13:00 19/10/2026')).toBeInTheDocument();
    });
  });
});
