const LINK_RENEWAL_LEAD_MS = 60_000;
// A stale timer longer than ~24.8 days overflows setTimeout and fires at once.
const MAX_LINK_STALE_MS = 24 * 60 * 60 * 1000;

/** Whether a signed link is expired or about to be, so a failed load means "renew", not "gone". */
export function linkNeedsRenewal(expiresAt: string, now = Date.now()): boolean {
  const expiry = Date.parse(expiresAt);
  return Number.isNaN(expiry) || now >= expiry - LINK_RENEWAL_LEAD_MS;
}

/**
 * What a file that failed to load needs: a fresh link when its link is expired or about to be and
 * none was asked for yet, otherwise the "unavailable" placeholder (a missing file must not loop).
 */
export function mediaErrorAction(
  renewalAsked: boolean,
  expiresAt: string | undefined,
  now = Date.now(),
): 'renew' | 'unavailable' {
  return !renewalAsked && expiresAt !== undefined && linkNeedsRenewal(expiresAt, now) ? 'renew' : 'unavailable';
}

/**
 * Milliseconds until the earliest link needs renewing; 0 when none is known, one is unreadable or
 * one already does. Missing expiries (an item with no link) are ignored.
 */
export function untilLinkRenewal(
  expiries: readonly (string | null | undefined)[],
  now = Date.now(),
): number {
  const known = expiries.filter((expiry): expiry is string => expiry != null).map((expiry) => Date.parse(expiry));
  if (known.length === 0 || known.some(Number.isNaN)) {
    return 0;
  }
  const untilRenewal = Math.min(...known) - LINK_RENEWAL_LEAD_MS - now;
  return Math.min(Math.max(0, untilRenewal), MAX_LINK_STALE_MS);
}
