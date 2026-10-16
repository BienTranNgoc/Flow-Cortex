import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { StopRunDialog } from './StopRunDialog';

const TEXT =
  "The current step will finish, then the run stops. Remaining steps won't run and won't be charged. Results of finished steps are kept.";

/**
 * jsdom has no `showModal`/`close`; these mirror the browser closely enough for the dialog's own
 * logic, and Escape fires `cancel` the way a browser does for an open modal dialog.
 */
function stubModalDialog() {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  Object.defineProperties(proto, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement) {
        if (!this.hasAttribute('open')) return;
        this.removeAttribute('open');
        this.dispatchEvent(new Event('close'));
      },
    },
  });
  const onEscape = (event: KeyboardEvent) => {
    const open = document.querySelector('dialog[open]');
    if (event.key === 'Escape' && open instanceof HTMLDialogElement) {
      if (open.dispatchEvent(new Event('cancel', { cancelable: true }))) open.close();
    }
  };
  document.addEventListener('keydown', onEscape);
  return () => {
    document.removeEventListener('keydown', onEscape);
    delete proto.showModal;
    delete proto.close;
  };
}

interface HarnessProps {
  chargedCredits?: number | null | undefined;
  stopping?: boolean;
  onKeep?: () => void;
  onStop?: () => void;
}

function Harness({ chargedCredits, stopping = false, onKeep, onStop }: Readonly<HarnessProps>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Stop workflow
      </button>
      <StopRunDialog
        open={open}
        chargedCredits={chargedCredits}
        stopping={stopping}
        onKeep={() => {
          onKeep?.();
          setOpen(false);
        }}
        onStop={() => onStop?.()}
      />
    </>
  );
}

async function openDialog() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Stop workflow' }));
  return { user, dialog: await screen.findByRole('dialog', { name: 'Stop this workflow?' }) };
}

describe('StopRunDialog', () => {
  let restore: () => void;
  beforeEach(() => {
    restore = stubModalDialog();
  });
  afterEach(() => {
    restore();
  });

  it('says what stopping does, offers both choices, and starts on "Keep running"', async () => {
    render(<Harness />);

    const { dialog } = await openDialog();

    expect(dialog).toHaveTextContent(TEXT);
    expect(screen.getByRole('button', { name: 'Keep running' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
  });

  it('closes on "Keep running" without stopping and gives focus back to "Stop workflow"', async () => {
    const onKeep = vi.fn();
    const onStop = vi.fn();
    render(<Harness onKeep={onKeep} onStop={onStop} />);

    const { user } = await openDialog();
    await user.click(screen.getByRole('button', { name: 'Keep running' }));

    expect(onKeep).toHaveBeenCalledTimes(1);
    expect(onStop).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stop workflow' })).toHaveFocus();
  });

  it('treats Escape as "Keep running"', async () => {
    const onKeep = vi.fn();
    const onStop = vi.fn();
    render(<Harness onKeep={onKeep} onStop={onStop} />);

    const { user } = await openDialog();
    await user.keyboard('{Escape}');

    expect(onKeep).toHaveBeenCalledTimes(1);
    expect(onStop).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stop workflow' })).toHaveFocus();
  });

  it('shows what the run has been charged so far when the amount is known', async () => {
    render(<Harness chargedCredits={60} />);

    const { dialog } = await openDialog();

    expect(dialog).toHaveTextContent('Charged for this run so far: 60 credits');
  });

  it.each([null, undefined])('shows no charged line when the amount is %s', async (amount) => {
    render(<Harness chargedCredits={amount} />);

    const { dialog } = await openDialog();

    expect(dialog).not.toHaveTextContent(/Charged for this run/);
  });

  it('reports "Stop" and shows it busy while the stop is being sent', async () => {
    const onStop = vi.fn();
    const { rerender } = render(<Harness onStop={onStop} />);

    const { user } = await openDialog();
    await user.click(screen.getByRole('button', { name: 'Stop' }));
    expect(onStop).toHaveBeenCalledTimes(1);

    rerender(<Harness onStop={onStop} stopping />);
    const busy = screen.getByRole('button', { name: 'Stopping…' });
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    await user.click(busy);
    expect(onStop).toHaveBeenCalledTimes(1);
  });
});
