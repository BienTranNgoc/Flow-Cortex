/**
 * jsdom has no `showModal`/`close`; these set and clear `open` and fire `close` like a browser, and
 * Escape fires `cancel` the way a browser does for an open modal dialog. Returns the function that
 * removes the stubs again.
 */
export function stubModalDialog(): () => void {
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
