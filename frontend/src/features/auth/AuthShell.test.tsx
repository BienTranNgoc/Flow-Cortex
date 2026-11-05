import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import { AuthShell } from './AuthShell';

const promo = {
  tag: viAuth.promo.tag,
  big: viAuth.promo.big,
  sub: viAuth.promo.sub,
  rows: [viAuth.promo.deadline, viAuth.promo.validity],
};

describe('AuthShell', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en');
    window.localStorage.clear();
  });

  it('shows no promo element and no empty frame when no promo is configured', () => {
    const { container } = render(
      <AuthShell promo={null}>
        <p>card</p>
      </AuthShell>,
    );
    expect(container.querySelector('.auth-promo')).toBeNull();
    expect(container.querySelector('.auth-shell__stage')?.textContent).not.toContain(promo.tag);
  });

  it('renders no promo by default', () => {
    const { container } = render(<AuthShell>{null}</AuthShell>);
    expect(container.querySelector('.auth-promo')).toBeNull();
    expect(screen.queryByText(promo.tag)).not.toBeInTheDocument();
  });

  it('renders a promo passed in with the words it carries', () => {
    const { container } = render(<AuthShell promo={promo}>{null}</AuthShell>);
    const promoNode = container.querySelector('.auth-promo');
    expect(promoNode).not.toBeNull();
    for (const words of [promo.tag, promo.big, promo.sub]) {
      expect(screen.getByText(words)).toBeInTheDocument();
    }
    expect(Array.from(promoNode!.querySelectorAll('dt'), (dt) => dt.textContent)).toEqual(
      promo.rows.map((row) => row.label),
    );
    for (const row of promo.rows) {
      expect(promoNode).toHaveTextContent(row.value);
    }
  });

  it('drops the banner image on a load error and the card form still submits', () => {
    const onSubmit = vi.fn((event: React.FormEvent) => event.preventDefault());
    const { container } = render(
      <AuthShell>
        <form onSubmit={onSubmit}>
          <button type="submit">go</button>
        </form>
      </AuthShell>,
    );
    const img = container.querySelector('img');
    expect(img).not.toBeNull();

    fireEvent.error(img!);

    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'go' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('puts the pose prop on the card mascot', () => {
    const { container, rerender } = render(<AuthShell>{null}</AuthShell>);
    expect(container.querySelector('.nodi-peek')).toHaveAttribute('data-pose', 'idle');

    rerender(<AuthShell pose="shy">{null}</AuthShell>);
    expect(container.querySelector('.nodi-peek')).toHaveAttribute('data-pose', 'shy');
  });

  it('holds the language toggle in the top bar and keeps a typed value across a switch', async () => {
    await i18n.changeLanguage('vi');
    const user = userEvent.setup();
    const { container } = render(
      <AuthShell>
        <input aria-label="first name" />
      </AuthShell>,
    );
    const bar = container.querySelector<HTMLElement>('.auth-shell__bar');
    expect(bar).not.toBeNull();
    expect(
      within(bar!).getByRole('group', { name: i18n.t('common.language.groupLabel') }),
    ).toBeInTheDocument();
    expect(screen.getByText(viAuth.shell.badge.offer)).toBeInTheDocument();

    await user.type(screen.getByLabelText('first name'), 'An');
    await user.click(within(bar!).getByRole('button', { name: 'EN English' }));

    expect(screen.getByLabelText('first name')).toHaveValue('An');
    expect(screen.queryByText(viAuth.shell.badge.offer)).not.toBeInTheDocument();
    expect(screen.getByText('FREE 1,000 CREDITS')).toBeInTheDocument();
    expect(window.localStorage.getItem('flow-cortex.locale')).toBe('en');
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });

    it('renders the banner words from the shell copy', () => {
      const { container } = render(<AuthShell>{null}</AuthShell>);
      const lines = container.querySelectorAll('.auth-stage__headline > span');
      expect(Array.from(lines, (line) => line.textContent)).toEqual([
        viAuth.shell.headline.line1,
        viAuth.shell.headline.line2,
      ]);
      expect(screen.getByText(viAuth.shell.badge.offer)).toBeInTheDocument();
      for (const chip of Object.values(viAuth.shell.chips)) {
        expect(screen.getByText(chip)).toBeInTheDocument();
      }
    });
  });
});
