import { render, screen } from '@testing-library/react';
import { ReasonDialog } from './ReasonDialog';

/** jsdom has no `showModal`; the dialog only needs to be marked open. */
function stubShowModal() {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  Object.defineProperty(proto, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    },
  });
  return () => {
    delete proto.showModal;
  };
}

function renderDialog(pending: boolean) {
  render(
    <ReasonDialog
      description="Turns the region gate on."
      pending={pending}
      onConfirm={() => {}}
      onCancel={() => {}}
    />,
  );
  return screen.getByRole('dialog');
}

function escapeIsBlocked(dialog: HTMLElement) {
  return !dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
}

describe('ReasonDialog', () => {
  let restore: () => void;
  beforeEach(() => {
    restore = stubShowModal();
  });
  afterEach(() => restore());

  it('lets the admin back out with Cancel or Esc before the change is sent', () => {
    const dialog = renderDialog(false);

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
    expect(escapeIsBlocked(dialog)).toBe(false);
  });

  it('offers no way to dismiss a change that is already on its way', () => {
    const dialog = renderDialog(true);

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(escapeIsBlocked(dialog)).toBe(true);
  });
});
