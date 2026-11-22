import { useNavigate } from 'react-router-dom';
import { isInAppLink, useMarkRead, type NotificationEntry } from './useNotifications';

/** Choosing a notification marks it read and opens its link when that stays inside the app. */
export function useChooseNotification(onChosen?: () => void) {
  const markRead = useMarkRead();
  const navigate = useNavigate();
  return (entry: NotificationEntry) => {
    if (!entry.read) {
      markRead.mutate(entry.id);
    }
    onChosen?.();
    if (isInAppLink(entry.link)) {
      navigate(entry.link);
    }
  };
}
