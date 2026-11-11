import { useEffect, useId, useRef, type ReactNode } from 'react';

interface ContentDialogProps {
  readonly title: string;
  readonly children: ReactNode;
  /** Called when the dialog closes by Escape, a backdrop click or the parent's own close. */
  readonly onClose: () => void;
  readonly wide?: boolean;
}

/** Modal over the editor; the parent mounts it while a decision is open and keeps the actions in `children`. */
export function ContentDialog({ title, children, onClose, wide = false }: ContentDialogProps) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className={wide ? 'content-dialog content-dialog--wide' : 'content-dialog'}
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
    >
      <div className="content-dialog__body">
        <h2 id={titleId}>{title}</h2>
        {children}
      </div>
    </dialog>
  );
}
