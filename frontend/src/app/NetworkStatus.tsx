import { ArrowsClockwise, Hourglass, WifiSlash } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import './NetworkStatus.css';
import { useNetworkStatus, type NetworkBannerMode } from './networkStatus';

const ICONS = {
  offline: WifiSlash,
  'rate-limited': Hourglass,
  reconnecting: ArrowsClockwise,
} satisfies Record<NetworkBannerMode, typeof WifiSlash>;

const MESSAGE_KEYS = {
  offline: 'common.networkStatus.offline',
  'rate-limited': 'common.networkStatus.rateLimited',
  reconnecting: 'common.networkStatus.reconnecting',
} satisfies Record<NetworkBannerMode, string>;

/** Fixed status region; always mounted so a screen reader announces the pill when it appears. */
export function NetworkStatus() {
  const { t } = useTranslation();
  const mode = useNetworkStatus();
  const Icon = mode ? ICONS[mode] : null;
  return (
    <div className="network-status" role="status" aria-live="polite">
      {mode && Icon ? (
        <div className="network-status__pill" data-mode={mode}>
          <Icon className="network-status__icon" size={16} aria-hidden="true" />
          <span>{t(MESSAGE_KEYS[mode])}</span>
        </div>
      ) : null}
    </div>
  );
}
