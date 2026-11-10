import { WarningCircle } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useBalance } from './useBalance';
import { creditsUsed, walletTier } from './walletTier';
import './BillingBanner.css';

/** Balance, credits used of the credits granted and a warning once the wallet runs low. */
export function BillingBanner() {
  const { t } = useTranslation();
  const wallet = useBalance();
  if (wallet === undefined) {
    return null;
  }
  const locale = activeLocale();
  const tier = walletTier(wallet.balance, wallet.granted);
  return (
    <section className="billing-banner" data-tier={tier} aria-label={t('credits.banner.title')}>
      <div className="billing-banner__figures">
        <span className="billing-banner__balance">
          {t('credits.banner.balance', { amount: formatNumber(wallet.balance, locale) })}
        </span>
        <span className="billing-banner__tier">{t(`credits.wallet.tier.${tier}`)}</span>
        <span className="billing-banner__used">
          {t('credits.banner.used', {
            used: formatNumber(creditsUsed(wallet.balance, wallet.granted), locale),
            granted: formatNumber(wallet.granted, locale),
          })}
        </span>
      </div>
      {tier !== 'ok' && (
        <p className="billing-banner__warning">
          <WarningCircle aria-hidden="true" weight="fill" />
          <span>{t(`credits.banner.warning.${tier}`)}</span>
        </p>
      )}
    </section>
  );
}
