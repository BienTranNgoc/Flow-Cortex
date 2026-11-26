import { useTranslation } from 'react-i18next';
import { NodiMark } from '@flow-cortex/ui-kit';
import { NODI_WORDMARK } from '../../app/brand';

/** The mark and wordmark at the top of every card of the signed-out and enrollment screens. */
export function AuthBrand() {
  const { t } = useTranslation();
  return (
    <div className="auth-shell__brand">
      <span className="auth-shell__brand-mark" aria-hidden="true">
        <NodiMark size={32} />
      </span>
      <div className="auth-shell__brand-text">
        <strong>{NODI_WORDMARK}</strong>
        <span>{t('common.header.console')}</span>
      </div>
    </div>
  );
}
