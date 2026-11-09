import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { creditsUsed, walletTier } from './walletTier';
import type { Wallet } from './useBalance';
import './WalletBadge.css';

export function WalletBadge({ wallet }: Readonly<{ wallet: Wallet }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const tier = walletTier(wallet.balance, wallet.granted);
  const amount = formatNumber(wallet.balance, locale);
  return (
    <Link
      to="/profile"
      className="wallet-badge"
      data-tier={tier}
      aria-label={t('credits.wallet.aria', { amount, tier: t(`credits.wallet.tier.${tier}`) })}
      title={t('credits.wallet.used', {
        used: formatNumber(creditsUsed(wallet.balance, wallet.granted), locale),
        granted: formatNumber(wallet.granted, locale),
      })}
    >
      <span className="wallet-badge__dot" aria-hidden="true" />
      <span>{t('common.nav.credits', { amount })}</span>
    </Link>
  );
}
