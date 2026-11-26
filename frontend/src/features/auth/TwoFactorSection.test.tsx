import { getToken, setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { TwoFactorSection } from './TwoFactorSection';
import { useMeQuery } from './useAuth';

const STATUS = 'GET /api/v1/auth/totp';
const SETUP = 'POST /api/v1/auth/totp/setup';
const ENABLE = 'POST /api/v1/auth/totp/enable';
const DISABLE = 'POST /api/v1/auth/totp/disable';
const REGENERATE = 'POST /api/v1/auth/totp/recovery-codes';

const OFF = {
  enabled: false,
  pending: false,
  required: false,
  verified: true,
  recoveryCodesLeft: 0,
};
const ON = {
  enabled: true,
  pending: false,
  required: false,
  verified: true,
  recoveryCodesLeft: 10,
};

const SETUP_BODY = {
  secret: 'JBSWY3DPEHPK3PXP',
  otpauthUri: 'otpauth://totp/Nodi:member1@flowcortex.dev?secret=JBSWY3DPEHPK3PXP&issuer=Nodi',
  qrDataUrl: 'data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=',
};

const CODES = Array.from({ length: 10 }, (_, index) => `ABCDE-2345${index % 8}`);

const START_BUTTON = 'Set up two-factor authentication';

function renderSection() {
  return renderRoute('/profile', '/profile', <TwoFactorSection />);
}

function reply(status: number, body?: unknown): FakeReply {
  return body === undefined ? { status } : { status, body };
}

describe('TwoFactorSection', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    restoreDialog = stubModalDialog();
    setToken('jwt-old', true);
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    window.localStorage.clear();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    void i18n.changeLanguage('en');
  });

  describe('turning it on', () => {
    async function enrollTo(status: { current: typeof OFF | typeof ON }) {
      const calls = stubApi({
        [STATUS]: () => reply(200, status.current),
        [SETUP]: reply(200, SETUP_BODY),
        [ENABLE]: () => {
          status.current = ON;
          return reply(200, { recoveryCodes: CODES, accessToken: 'jwt-new', expiresIn: 900 });
        },
      });
      const user = userEvent.setup();
      renderSection();
      await user.click(await screen.findByRole('button', { name: START_BUTTON }));
      return { calls, user };
    }

    it('shows the QR code and the manual key, then confirms a code and shows the recovery codes once', async () => {
      const status = { current: OFF as typeof OFF | typeof ON };
      const { calls, user } = await enrollTo(status);

      expect(await screen.findByRole('img', { name: /QR code to scan/ })).toHaveAttribute(
        'src',
        SETUP_BODY.qrDataUrl,
      );
      expect(screen.getByLabelText('Cannot scan? Enter this key in your app')).toHaveValue(
        'JBSW Y3DP EHPK 3PXP',
      );

      await user.type(screen.getByLabelText('Code from your app'), '123456');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));

      const group = await screen.findByRole('group', {
        name: 'Two-factor authentication is on. Save your recovery codes',
      });
      expect(
        within(group)
          .getAllByRole('listitem')
          .map((item) => item.textContent),
      ).toEqual(CODES);
      expect(calls.find((call) => call.path === '/api/v1/auth/totp/enable')?.body).toEqual({
        code: '123456',
      });
      expect(getToken()).toBe('jwt-new');
      expect(window.localStorage.getItem('flowcortex.auth.token')).toBe('jwt-new');

      await user.click(screen.getByRole('button', { name: 'I saved them' }));

      expect(await screen.findByText('Two-factor authentication is on.')).toBeInTheDocument();
      expect(screen.getByText('10 recovery codes left.')).toBeInTheDocument();
      expect(screen.queryByText(CODES[0]!)).not.toBeInTheDocument();
    });

    it('copies the recovery codes and downloads them as a .txt file', async () => {
      const { user } = await enrollTo({ current: OFF });
      await user.type(await screen.findByLabelText('Code from your app'), '123456');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));
      await screen.findByRole('button', { name: 'I saved them' });

      await user.click(screen.getByRole('button', { name: 'Copy codes' }));
      expect(await screen.findByText('Codes copied to the clipboard.')).toBeInTheDocument();
      expect(await navigator.clipboard.readText()).toBe(CODES.join('\n'));

      const createObjectURL = vi.fn((_blob: Blob) => 'blob:codes');
      Object.assign(URL, { createObjectURL });
      let downloaded: string | undefined;
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
        this: HTMLAnchorElement,
      ) {
        downloaded = this.download;
      });
      await user.click(screen.getByRole('button', { name: 'Download .txt' }));

      expect(downloaded).toBe('nodi-recovery-codes.txt');
      const blob = createObjectURL.mock.calls[0]![0];
      const text = await new Promise<string>((resolve) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.readAsText(blob);
      });
      expect(text).toContain(CODES.join('\n'));
    });

    it('says a wrong code is wrong and stays on the QR code so the member can try again', async () => {
      stubApi({
        [STATUS]: reply(200, OFF),
        [SETUP]: reply(200, SETUP_BODY),
        [ENABLE]: reply(422, {
          status: 422,
          code: 'TOTP_INVALID_CODE',
          detail: 'The code is not valid.',
        }),
      });
      const user = userEvent.setup();
      renderSection();
      await user.click(await screen.findByRole('button', { name: START_BUTTON }));

      await user.type(await screen.findByLabelText('Code from your app'), '123456');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'That code is not correct. Check the code and try again.',
      );
      expect(screen.getByLabelText('Code from your app')).toHaveValue('');
      expect(getToken()).toBe('jwt-old');
    });

    it('refuses a code of the wrong shape without sending it', async () => {
      const { calls, user } = await enrollTo({ current: OFF });

      await user.type(await screen.findByLabelText('Code from your app'), '12');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Enter the 6-digit code from your app.',
      );
      expect(calls.filter((call) => call.path === '/api/v1/auth/totp/enable')).toEqual([]);
    });

    it('shows when the lock ends and disables Turn on until then', async () => {
      const lockedUntil = new Date(Date.now() + 10 * 60_000).toISOString();
      stubApi({
        [STATUS]: reply(200, OFF),
        [SETUP]: reply(200, SETUP_BODY),
        [ENABLE]: reply(423, { status: 423, code: 'TOTP_LOCKED', detail: 'Locked.', lockedUntil }),
      });
      const user = userEvent.setup();
      renderSection();
      await user.click(await screen.findByRole('button', { name: START_BUTTON }));
      await user.type(await screen.findByLabelText('Code from your app'), '123456');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        /^Too many wrong codes\. Try again at .+\.$/,
      );
      expect(screen.getByRole('button', { name: 'Turn on' })).toBeDisabled();
    });

    it('goes back to the start when Cancel is pressed', async () => {
      const { user } = await enrollTo({ current: OFF });

      await user.click(await screen.findByRole('button', { name: 'Cancel' }));

      expect(await screen.findByRole('button', { name: START_BUTTON })).toBeInTheDocument();
      expect(screen.queryByRole('img', { name: /QR code to scan/ })).not.toBeInTheDocument();
    });

    it('keeps the page above it mounted by giving the new token its /me answer at once', async () => {
      const seen: string[] = [];
      function MeProbe() {
        const me = useMeQuery();
        seen.push(me.isPending ? 'checking' : 'ready');
        return null;
      }
      const status = { current: OFF as typeof OFF | typeof ON };
      stubApi({
        'GET /api/v1/auth/me': reply(200, {
          id: 'u1',
          email: 'member1@flowcortex.dev',
          role: 'MEMBER',
        }),
        [STATUS]: () => reply(200, status.current),
        [SETUP]: reply(200, SETUP_BODY),
        [ENABLE]: () => {
          status.current = ON;
          return reply(200, { recoveryCodes: CODES, accessToken: 'jwt-new', expiresIn: 900 });
        },
      });
      const user = userEvent.setup();
      renderRoute(
        '/profile',
        '/profile',
        <>
          <MeProbe />
          <TwoFactorSection />
        </>,
      );
      await user.click(await screen.findByRole('button', { name: START_BUTTON }));
      await waitFor(() => expect(seen.at(-1)).toBe('ready'));
      const before = seen.length;

      await user.type(await screen.findByLabelText('Code from your app'), '123456');
      await user.click(screen.getByRole('button', { name: 'Turn on' }));
      await screen.findByRole('button', { name: 'I saved them' });

      expect(getToken()).toBe('jwt-new');
      expect(seen.slice(before)).not.toContain('checking');
    });
  });

  describe('when it is on', () => {
    it('shows the codes left and offers regenerate and turn-off', async () => {
      stubApi({ [STATUS]: reply(200, { ...ON, recoveryCodesLeft: 7 }) });
      renderSection();

      expect(await screen.findByText('Two-factor authentication is on.')).toBeInTheDocument();
      expect(screen.getByText('7 recovery codes left.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Get new recovery codes' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Turn off' })).toBeInTheDocument();
    });

    it('says one code in the singular', async () => {
      stubApi({ [STATUS]: reply(200, { ...ON, recoveryCodesLeft: 1 }) });
      renderSection();

      expect(await screen.findByText('1 recovery code left.')).toBeInTheDocument();
    });

    it('hides turn-off while the role requires two-factor authentication', async () => {
      stubApi({ [STATUS]: reply(200, { ...ON, required: true }) });
      renderSection();

      expect(await screen.findByText(/must keep two-factor authentication on/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Turn off' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Get new recovery codes' })).toBeInTheDocument();
    });

    it('turns it off after a code is entered in the dialog', async () => {
      const status = { current: ON as typeof OFF | typeof ON };
      const calls = stubApi({
        [STATUS]: () => reply(200, status.current),
        [DISABLE]: () => {
          status.current = OFF;
          return reply(204);
        },
      });
      const user = userEvent.setup();
      renderSection();

      await user.click(await screen.findByRole('button', { name: 'Turn off' }));
      const dialog = screen.getByRole('dialog', { name: 'Turn off two-factor authentication' });
      await user.type(within(dialog).getByLabelText('Code'), 'abcde-23456');
      await user.click(within(dialog).getByRole('button', { name: 'Turn off' }));

      expect(
        await screen.findByText('Two-factor authentication is off.', { selector: '[role=status]' }),
      ).toBeInTheDocument();
      expect(calls.find((call) => call.path === '/api/v1/auth/totp/disable')?.body).toEqual({
        code: 'abcde-23456',
      });
      expect(await screen.findByRole('button', { name: START_BUTTON })).toBeInTheDocument();
    });

    it('keeps the dialog open with the reason when the code is refused', async () => {
      stubApi({
        [STATUS]: reply(200, ON),
        [DISABLE]: reply(422, {
          status: 422,
          code: 'TOTP_INVALID_CODE',
          detail: 'The code is not valid.',
        }),
      });
      const user = userEvent.setup();
      renderSection();

      await user.click(await screen.findByRole('button', { name: 'Turn off' }));
      const dialog = screen.getByRole('dialog', { name: 'Turn off two-factor authentication' });
      await user.type(within(dialog).getByLabelText('Code'), '123456');
      await user.click(within(dialog).getByRole('button', { name: 'Turn off' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent(
        'That code is not correct.',
      );
      expect(dialog).toHaveAttribute('open');
    });

    it('asks for an authenticator code, not a recovery code, before new recovery codes', async () => {
      const calls = stubApi({
        [STATUS]: reply(200, ON),
        [REGENERATE]: reply(200, { recoveryCodes: CODES }),
      });
      const user = userEvent.setup();
      renderSection();

      await user.click(await screen.findByRole('button', { name: 'Get new recovery codes' }));
      const dialog = screen.getByRole('dialog', { name: 'Get new recovery codes' });
      await user.type(within(dialog).getByLabelText('Code'), 'abcde-23456');
      await user.click(within(dialog).getByRole('button', { name: 'Get new codes' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(
        'Enter the 6-digit code from your app.',
      );
      expect(calls.filter((call) => call.path === '/api/v1/auth/totp/recovery-codes')).toEqual([]);

      await user.clear(within(dialog).getByLabelText('Code'));
      await user.type(within(dialog).getByLabelText('Code'), '123456');
      await user.click(within(dialog).getByRole('button', { name: 'Get new codes' }));

      const group = await screen.findByRole('group', { name: 'Your new recovery codes' });
      expect(within(group).getAllByRole('listitem')).toHaveLength(10);
      expect(dialog).not.toHaveAttribute('open');
    });
  });

  describe('before it loads', () => {
    it('shows a loading line, then a retry when the status cannot be read', async () => {
      let attempts = 0;
      stubApi({
        [STATUS]: () => {
          attempts += 1;
          return attempts === 1 ? reply(500, { status: 500, detail: 'boom' }) : reply(200, OFF);
        },
      });
      const user = userEvent.setup();
      renderSection();

      expect(screen.getByText('Loading two-factor status…')).toBeInTheDocument();
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Could not load two-factor status.',
      );

      await user.click(screen.getByRole('button', { name: 'Retry' }));

      expect(await screen.findByRole('button', { name: START_BUTTON })).toBeInTheDocument();
    });

    it('tells an account that needs it, and has not set it up, to set it up', async () => {
      stubApi({ [STATUS]: reply(200, { ...OFF, required: true }) });
      renderSection();

      expect(
        await screen.findByText(/Your role needs two-factor authentication/),
      ).toBeInTheDocument();
    });

    it('is written in Vietnamese when the language is Vietnamese', async () => {
      await i18n.changeLanguage('vi');
      stubApi({ [STATUS]: reply(200, ON) });
      renderSection();

      expect(await screen.findByRole('heading', { name: 'Xác thực hai lớp' })).toBeInTheDocument();
      expect(await screen.findByText('Xác thực hai lớp đang bật.')).toBeInTheDocument();
      expect(screen.getByText('Còn 10 mã khôi phục.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Lấy mã khôi phục mới' })).toBeInTheDocument();
    });
  });
});
