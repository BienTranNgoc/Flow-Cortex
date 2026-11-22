import { useEffect, useRef, type RefObject } from 'react';

/**
 * Opens and closes a native modal `<dialog>` with `open`: focus starts on `initialFocus` and goes
 * back to whatever held it before the dialog opened.
 */
export function useModalDialog(open: boolean, initialFocus: RefObject<HTMLElement>) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        initialFocus.current?.focus();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open, initialFocus]);

  return dialogRef;
}
