import { useTranslation } from 'react-i18next';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import type { NotificationEntry } from './useNotifications';
import './NotificationRow.css';

const TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

interface NotificationRowProps {
  readonly entry: NotificationEntry;
  readonly onChoose: (entry: NotificationEntry) => void;
}

export function NotificationRow({ entry, onChoose }: Readonly<NotificationRowProps>) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="notification-row"
      data-unread={entry.read ? undefined : 'true'}
      onClick={() => onChoose(entry)}
    >
      <span className="notification-row__mark" aria-hidden="true" />
      <span className="notification-row__text">
        <span className="notification-row__title">
          {!entry.read && <span className="visually-hidden">{t('notifications.unreadMark')} </span>}
          {entry.title}
        </span>
        {entry.body && <span className="notification-row__body">{entry.body}</span>}
        {entry.createdAt && (
          <time className="notification-row__time" dateTime={entry.createdAt}>
            {formatDateTime(entry.createdAt, activeLocale(), TIME_FORMAT)}
          </time>
        )}
      </span>
    </button>
  );
}
