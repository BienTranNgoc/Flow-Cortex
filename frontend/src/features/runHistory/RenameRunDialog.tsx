import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useModalDialog } from './useModalDialog';

export const RUN_TITLE_MAX = 120;

interface RenameRunDialogProps {
  open: boolean;
  /** The title the run has now; empty when it shows its workflow name. */
  currentTitle: string;
  saving: boolean;
  error: string | null;
  onCancel: () => void;
  onSave: (title: string) => void;
}

/** Renames a run: the text field starts focused, Enter saves, Escape cancels; an empty name clears the title. */
export function RenameRunDialog({
  open,
  currentTitle,
  saving,
  error,
  onCancel,
  onSave,
}: Readonly<RenameRunDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const inputId = useId();
  const hintId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useModalDialog(open, inputRef);
  const [value, setValue] = useState(currentTitle);

  useEffect(() => {
    if (open) setValue(currentTitle);
  }, [open, currentTitle]);

  return (
    <dialog
      ref={dialogRef}
      className="run-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!saving) onCancel();
      }}
      onClose={() => {
        if (open && !saving) onCancel();
      }}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!saving) onSave(value);
        }}
      >
        <h2 id={titleId}>{t('runHistory.rename.title')}</h2>
        <label htmlFor={inputId} className="run-dialog__label">
          {t('runHistory.rename.label')}
        </label>
        <input
          ref={inputRef}
          id={inputId}
          className="run-dialog__input"
          type="text"
          maxLength={RUN_TITLE_MAX}
          value={value}
          aria-describedby={hintId}
          onChange={(event) => setValue(event.target.value)}
        />
        <p id={hintId} className="run-dialog__hint">
          {t('runHistory.rename.hint', { max: RUN_TITLE_MAX })}
        </p>
        {error && (
          <p className="run-dialog__error" role="alert">
            {error}
          </p>
        )}
        <div className="run-dialog__actions">
          <button
            type="button"
            aria-disabled={saving}
            onClick={() => {
              if (!saving) onCancel();
            }}
          >
            {t('runHistory.rename.cancel')}
          </button>
          <button type="submit" className="run-dialog__primary" aria-disabled={saving} aria-busy={saving}>
            {saving ? t('runHistory.rename.saving') : t('runHistory.rename.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
