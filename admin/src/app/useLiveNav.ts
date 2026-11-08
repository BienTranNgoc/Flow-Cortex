import { useOpenCount } from '../features/support/useOpenCount';
import { NAV, type NavGroup, type NavLiveCount } from './nav';
import { can } from './permissions';

/** `NAV` with each `liveCount` entry's `badge` filled from its live source. */
export function useLiveNav(role: string | undefined): NavGroup[] {
  const counts: Record<NavLiveCount, number | undefined> = {
    openSupportRequests: useOpenCount(can(role, 'support.manage')).data,
  };
  return NAV.map((group) => ({
    ...group,
    items: group.items.map((item) => {
      const count = item.liveCount ? counts[item.liveCount] : undefined;
      return count === undefined ? item : { ...item, badge: count };
    }),
  }));
}
