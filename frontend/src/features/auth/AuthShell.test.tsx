import { fireEvent, render, screen } from '@testing-library/react';
import { AuthShell } from './AuthShell';
import { authCopy } from './authCopy';

describe('AuthShell', () => {
  it('shows no promo element and no empty frame when no promo is configured', () => {
    const { container } = render(
      <AuthShell promo={null}>
        <p>card</p>
      </AuthShell>,
    );
    expect(container.querySelector('.auth-promo')).toBeNull();
    expect(container.querySelector('.auth-shell__stage')?.textContent).not.toContain(
      authCopy.promo.tag,
    );
  });

  it('renders no promo by default', () => {
    const { container } = render(<AuthShell>{null}</AuthShell>);
    expect(container.querySelector('.auth-promo')).toBeNull();
    expect(screen.queryByText(authCopy.promo.tag)).not.toBeInTheDocument();
  });

  it('renders a promo passed in with the words from the auth copy', () => {
    const { container } = render(<AuthShell promo={authCopy.promo}>{null}</AuthShell>);
    const promo = container.querySelector('.auth-promo');
    expect(promo).not.toBeNull();
    const { tag, big, sub, rows } = authCopy.promo;
    for (const words of [tag, big, sub]) {
      expect(screen.getByText(words)).toBeInTheDocument();
    }
    expect(Array.from(promo!.querySelectorAll('dt'), (dt) => dt.textContent)).toEqual(
      rows.map((row) => row.label),
    );
    for (const row of rows) {
      expect(promo).toHaveTextContent(row.value);
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

  it('renders the banner words from the shell copy', () => {
    const { container } = render(<AuthShell>{null}</AuthShell>);
    const lines = container.querySelectorAll('.auth-stage__headline > span');
    expect(Array.from(lines, (line) => line.textContent)).toEqual([...authCopy.shell.headline]);
    expect(screen.getByText(authCopy.shell.badge.offer)).toBeInTheDocument();
    for (const chip of authCopy.shell.chips) {
      expect(screen.getByText(chip)).toBeInTheDocument();
    }
  });

  it('puts the pose prop on the card mascot', () => {
    const { container, rerender } = render(<AuthShell>{null}</AuthShell>);
    expect(container.querySelector('.nodi-peek')).toHaveAttribute('data-pose', 'idle');

    rerender(<AuthShell pose="shy">{null}</AuthShell>);
    expect(container.querySelector('.nodi-peek')).toHaveAttribute('data-pose', 'shy');
  });
});
