/**
 * jsdom has no `showModal`/`close`; these set and clear `open` and fire `close` like a browser.
 * Returns the function that removes the stubs again.
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
  return () => {
    delete proto.showModal;
    delete proto.close;
  };
}
