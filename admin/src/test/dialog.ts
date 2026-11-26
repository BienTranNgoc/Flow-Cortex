/**
 * jsdom has no `showModal`/`close` on `<dialog>`. Call inside a `describe`: before each test it
 * installs versions that toggle the `open` attribute (and fire `close`), after each it restores
 * whatever was there.
 */
export function stubDialogElement() {
  const showModalDescriptor = Object.getOwnPropertyDescriptor(
    HTMLDialogElement.prototype,
    'showModal',
  );
  const closeDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.setAttribute('open', '');
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.removeAttribute('open');
        this.dispatchEvent(new Event('close'));
      },
    });
  });
  afterEach(() => {
    for (const [name, descriptor] of [
      ['showModal', showModalDescriptor],
      ['close', closeDescriptor],
    ] as const) {
      if (descriptor) {
        Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
      } else {
        Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      }
    }
  });
}
