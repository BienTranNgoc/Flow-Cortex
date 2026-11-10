import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { NotificationPreferencesPage } from './NotificationPreferencesPage';

const ALL_ON = { inApp: true, careEmail: true, marketingEmail: true };

function renderPage() {
  return renderRoute('/', '/', <NotificationPreferencesPage />);
}

describe('NotificationPreferencesPage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('puts the switch back and announces an error when the save fails', async () => {
    stubApi({
      'GET /api/v1/notifications/preferences': { status: 200, body: ALL_ON },
      'PATCH /api/v1/notifications/preferences': { status: 500, body: {} },
    });
    const user = userEvent.setup();

    renderPage();
    const toggle = await screen.findByRole('switch', { name: 'News and offers by email' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    await user.click(toggle);

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be saved');
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
  });

  it('toggles with Space and saves only the changed field', async () => {
    let saved = ALL_ON;
    const calls = stubApi({
      'GET /api/v1/notifications/preferences': () => ({ status: 200, body: saved }),
      'PATCH /api/v1/notifications/preferences': () => {
        saved = { ...ALL_ON, careEmail: false };
        return { status: 200, body: saved };
      },
    });
    const user = userEvent.setup();

    renderPage();
    const toggle = await screen.findByRole('switch', { name: 'Tips and reminders by email' });
    toggle.focus();
    await user.keyboard(' ');

    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
    const patch = calls.find((call) => call.method === 'PATCH');
    expect(patch?.body).toEqual({ careEmail: false });
  });
});
