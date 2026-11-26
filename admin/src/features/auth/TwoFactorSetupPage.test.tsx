import { getToken, setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { TwoFactorSetupPage } from './TwoFactorSetupPage';

const SETUP_PATH = 'POST /api/v1/auth/totp/setup';
const ENABLE_PATH = 'POST /api/v1/auth/totp/enable';
const SETUP = {
  secret: 'JBSWY3DPEHPK3PXP',
  otpauthUri: 'otpauth://totp/Nodi:admin@flowcortex.dev?secret=JBSWY3DPEHPK3PXP&issuer=Nodi',
  qrDataUrl: 'data:image/svg+xml;base64,PHN2Zy8+',
};
const RECOVERY_CODES = Array.from({ length: 10 }, (_, i) => `ABCDE-2345${i}`);

function renderSetup() {
  return renderRoute('/setup', '/setup', <TwoFactorSetupPage email="admin@flowcortex.dev" />);
}

async function enterCode(user: ReturnType<typeof userEvent.setup>, code: string) {
  await user.type(await screen.findByLabelText('6-digit code'), code);
  await user.click(screen.getByRole('button', { name: 'Turn on' }));
}

beforeEach(() => setToken('admin-token-setup-page', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('TwoFactorSetupPage', () => {
  it('starts one setup, shows the QR code and the key in groups of four', async () => {
    const calls = stubApi({ [SETUP_PATH]: { status: 200, body: SETUP } });
    renderSetup();

    expect(await screen.findByRole('img', { name: /QR code/ })).toHaveAttribute(
      'src',
      SETUP.qrDataUrl,
    );
    expect(screen.getByText('JBSW Y3DP EHPK 3PXP')).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/totp/setup')).toHaveLength(1);
  });

  it('offers a retry when the setup cannot start', async () => {
    let attempt = 0;
    stubApi({
      [SETUP_PATH]: () => {
        attempt += 1;
        return attempt === 1
          ? { status: 500, body: { status: 500 } }
          : { status: 200, body: SETUP };
      },
    });
    const user = userEvent.setup();
    renderSetup();

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('img', { name: /QR code/ })).toBeInTheDocument();
  });

  it('refuses a code that is not six digits before calling the server', async () => {
    const calls = stubApi({ [SETUP_PATH]: { status: 200, body: SETUP } });
    const user = userEvent.setup();
    renderSetup();

    await enterCode(user, 'ABCDE23456');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter the 6-digit code or a recovery code.',
    );
    expect(calls.some((call) => call.path === '/api/v1/auth/totp/enable')).toBe(false);
  });

  it('says a wrong code is not valid and empties the field', async () => {
    stubApi({
      [SETUP_PATH]: { status: 200, body: SETUP },
      [ENABLE_PATH]: { status: 422, body: { status: 422, code: 'TOTP_INVALID_CODE' } },
    });
    const user = userEvent.setup();
    renderSetup();

    await enterCode(user, '000000');

    expect(await screen.findByRole('alert')).toHaveTextContent('The code is not valid.');
    expect(screen.getByLabelText('6-digit code')).toHaveValue('');
  });

  it('keeps submit off while the account is locked', async () => {
    const lockedUntil = new Date(Date.now() + 15 * 60_000).toISOString();
    stubApi({
      [SETUP_PATH]: { status: 200, body: SETUP },
      [ENABLE_PATH]: { status: 423, body: { status: 423, code: 'TOTP_LOCKED', lockedUntil } },
    });
    const user = userEvent.setup();
    renderSetup();

    await enterCode(user, '123456');

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many wrong codes.');
    expect(screen.getByRole('button', { name: 'Turn on' })).toBeDisabled();
  });

  it('starts over when the pending setup is gone', async () => {
    let setups = 0;
    stubApi({
      [SETUP_PATH]: () => {
        setups += 1;
        return { status: 200, body: SETUP };
      },
      [ENABLE_PATH]: { status: 409, body: { status: 409, code: 'TOTP_NOT_SET_UP' } },
    });
    const user = userEvent.setup();
    renderSetup();

    await enterCode(user, '123456');
    expect(await screen.findByRole('alert')).toHaveTextContent('The setup was reset.');
    await user.click(screen.getByRole('button', { name: 'Start again' }));

    expect(await screen.findByLabelText('6-digit code')).toBeInTheDocument();
    expect(setups).toBe(2);
  });

  describe('once on', () => {
    async function turnOn() {
      stubApi({
        [SETUP_PATH]: { status: 200, body: SETUP },
        [ENABLE_PATH]: {
          status: 200,
          body: { recoveryCodes: RECOVERY_CODES, accessToken: 'enrolled-token', expiresIn: 900 },
        },
      });
      const user = userEvent.setup();
      renderSetup();
      await enterCode(user, '123 456');
      await screen.findByRole('heading', { name: 'Save your recovery codes' });
      return user;
    }

    it('shows the ten codes and keeps the old token until the admin continues', async () => {
      await turnOn();

      expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual(
        RECOVERY_CODES,
      );
      expect(getToken()).toBe('admin-token-setup-page');
    });

    it('stores the session that passed TOTP on continue', async () => {
      const user = await turnOn();

      await user.click(screen.getByRole('button', { name: 'Continue to the console' }));

      expect(getToken()).toBe('enrolled-token');
    });

    it('copies the codes one per line', async () => {
      const user = await turnOn();

      await user.click(screen.getByRole('button', { name: 'Copy codes' }));

      expect(await screen.findByText('Codes copied.')).toBeInTheDocument();
      expect(await navigator.clipboard.readText()).toBe(RECOVERY_CODES.join('\n'));
    });

    it('says so when the clipboard refuses', async () => {
      const user = await turnOn();
      vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));

      await user.click(screen.getByRole('button', { name: 'Copy codes' }));

      expect(await screen.findByText(/Could not copy/)).toBeInTheDocument();
    });

    it('downloads the codes as a text file', async () => {
      const user = await turnOn();
      Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:codes'), revokeObjectURL: vi.fn() });
      const clicked: string[] = [];
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
        this: HTMLAnchorElement,
      ) {
        clicked.push(this.download);
      });

      await user.click(screen.getByRole('button', { name: 'Download as .txt' }));

      expect(clicked).toEqual(['flow-cortex-recovery-codes.txt']);
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:codes');
    });
  });

  it('signs out from the setup screen', async () => {
    const calls = stubApi({
      [SETUP_PATH]: { status: 200, body: SETUP },
      'POST /api/v1/auth/logout': { status: 204 },
    });
    const user = userEvent.setup();
    renderSetup();

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(calls.some((call) => call.path === '/api/v1/auth/logout')).toBe(true);
  });

  it('shows the screen in Vietnamese', async () => {
    await i18n.changeLanguage('vi');
    try {
      stubApi({ [SETUP_PATH]: { status: 200, body: SETUP } });
      renderSetup();

      expect(await screen.findByRole('heading', { name: viAuth.setup.title })).toBeInTheDocument();
      expect(await screen.findByLabelText(viAuth.setup.code)).toBeInTheDocument();
      expect(screen.getByRole('img', { name: viAuth.setup.qrAlt })).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage('en');
    }
  });
});
