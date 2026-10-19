import { useTranslation } from 'react-i18next';

/**
 * A start refused for credits. "Run anyway" appears only when `onRunAnyway` is given; while
 * `busy` it stays focusable and a press changes nothing.
 */
export function CreditsRefusal({
  message,
  busy,
  onRunAnyway,
}: Readonly<{ message: string; busy: boolean; onRunAnyway?: (() => void) | undefined }>) {
  const { t } = useTranslation();
  return (
    <div role="alert" className="form-error">
      <p>{message}</p>
      {onRunAnyway && (
        <button type="button" aria-busy={busy} onClick={() => !busy && onRunAnyway()}>
          {t('credits.runAnyway')}
        </button>
      )}
    </div>
  );
}
