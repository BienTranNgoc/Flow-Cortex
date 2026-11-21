import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type RecordedCall } from '../../test/fakeApi';
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

  it('sends quick toggles in click order and never shows the older value', async () => {
    let saved = ALL_ON;
    let releaseFirst: () => void = () => undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    let patchCount = 0;
    const calls: RecordedCall[] = stubApi({
      'GET /api/v1/notifications/preferences': () => ({ status: 200, body: saved }),
      'PATCH /api/v1/notifications/preferences': async () => {
        patchCount += 1;
        const body = calls[calls.length - 1]?.body as object;
        if (patchCount === 1) await firstGate;
        saved = { ...saved, ...body };
        return { status: 200, body: saved };
      },
    });
    const user = userEvent.setup();

    renderPage();
    const toggle = await screen.findByRole('switch', { name: 'News and offers by email' });
    await user.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
    await user.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));

    const patches = () => calls.filter((call) => call.method === 'PATCH');
    expect(patches().map((call) => call.body)).toEqual([{ marketingEmail: false }]);

    releaseFirst();
    await waitFor(() =>
      expect(patches().map((call) => call.body)).toEqual([{ marketingEmail: false }, { marketingEmail: true }]),
    );
    await waitFor(() => expect(calls.filter((call) => call.method === 'GET')).toHaveLength(2));
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(saved.marketingEmail).toBe(true);
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
