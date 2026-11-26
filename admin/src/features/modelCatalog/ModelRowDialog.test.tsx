import { setToken, type CatalogModel } from '@flow-cortex/api-client';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { ModelRowDialog } from './ModelRowDialog';

/** jsdom has no modal dialog; `showModal` marks it open and `close` fires the native close event. */
function stubModal() {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  const original = { showModal: proto.showModal, close: proto.close };
  Object.defineProperty(proto, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    },
  });
  Object.defineProperty(proto, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    },
  });
  return () => {
    Object.defineProperty(proto, 'showModal', { configurable: true, value: original.showModal });
    Object.defineProperty(proto, 'close', { configurable: true, value: original.close });
  };
}

const ENTRY: CatalogModel = {
  capability: 'text.generate',
  modelKey: 'gemini-3.8-flash',
  providerKey: 'gemini',
  mode: 'text',
  enabled: true,
  notImplemented: false,
  createdAt: '2026-11-20T00:00:00Z',
  updatedAt: '2026-11-20T00:00:00Z',
};

function renderDialog(onClose: () => void) {
  setToken('t', false);
  stubApi({ 'GET /api/v1/admin/ai/model-descriptors': { status: 200, body: [] } });
  renderRoute(
    '/',
    '/',
    <ModelRowDialog
      capability="text.generate"
      entry={ENTRY}
      onSaved={() => {}}
      onClose={onClose}
    />,
  );
  return screen.getByRole('dialog', { hidden: true });
}

describe('ModelRowDialog', () => {
  let restore: () => void;
  beforeEach(() => {
    restore = stubModal();
  });
  afterEach(() => restore());

  it('keeps the form open when the backdrop is clicked, so a half-typed edit is not lost', () => {
    const onClose = vi.fn();
    const dialog = renderDialog(onClose);

    fireEvent.click(dialog);

    expect(dialog).toHaveAttribute('open');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes through Cancel', async () => {
    const onClose = vi.fn();
    renderDialog(onClose);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel', hidden: true }));

    expect(onClose).toHaveBeenCalledOnce();
  });
});
