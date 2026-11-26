import { useEffect, useRef } from 'react';

/** A ref for a `<dialog>` that opens it as a modal once it mounts. */
export function useModalDialog() {
  const dialogRef = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);
  return dialogRef;
}
