import { useTranslation } from 'react-i18next';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';

/** Placeholder rows under a busy region; the status text is for assistive technology only. */
export function LegalLoading() {
  const { t } = useTranslation();
  return (
    <div role="status" aria-busy="true" className="legal-state">
      <span className="visually-hidden">{t('legal.common.loading')}</span>
      <Skeleton shape="row" count={3} />
    </div>
  );
}

export function LegalError({ message, onRetry }: Readonly<{ message: string; onRetry: () => void }>) {
  const { t } = useTranslation();
  return (
    <div className="legal-state">
      <StatusBlock kind="error" message={message} action={{ label: t('legal.common.retry'), onClick: onRetry }} />
    </div>
  );
}

/** A definite "no such page" answer: unknown, unpublished or malformed. */
export function LegalNotAvailable({ message }: Readonly<{ message: string }>) {
  return (
    <p role="status" className="legal-state legal-state--empty">
      {message}
    </p>
  );
}
