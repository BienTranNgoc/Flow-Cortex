import { linkNeedsRenewal, mediaErrorAction, untilLinkRenewal } from './signedLinks';

const NOW = Date.parse('2026-11-10T08:00:00Z');
const at = (offsetMs: number) => new Date(NOW + offsetMs).toISOString();

describe('linkNeedsRenewal', () => {
  it('needs renewal from 60 s before expiry and not at 61 s', () => {
    const expiresAt = at(60_000);

    expect(linkNeedsRenewal(expiresAt, NOW)).toBe(true);
    expect(linkNeedsRenewal(at(61_000), NOW)).toBe(false);
    expect(linkNeedsRenewal(at(-1_000), NOW)).toBe(true);
  });

  it('treats an unreadable expiry as needing renewal', () => {
    expect(linkNeedsRenewal('not a date', NOW)).toBe(true);
    expect(linkNeedsRenewal('', NOW)).toBe(true);
  });
});

describe('mediaErrorAction', () => {
  it('asks for a fresh link when the link is near its end and nobody asked yet', () => {
    expect(mediaErrorAction(false, at(30_000), NOW)).toBe('renew');
    expect(mediaErrorAction(false, at(-5_000), NOW)).toBe('renew');
  });

  it('shows the placeholder when a fresh link was already asked for', () => {
    expect(mediaErrorAction(true, at(30_000), NOW)).toBe('unavailable');
  });

  it('shows the placeholder for a link far from its end, since a missing file is not an expiry', () => {
    expect(mediaErrorAction(false, at(3_600_000), NOW)).toBe('unavailable');
  });

  it('shows the placeholder when there is no expiry to renew against', () => {
    expect(mediaErrorAction(false, undefined, NOW)).toBe('unavailable');
  });
});

describe('untilLinkRenewal', () => {
  it('waits until 60 s before the earliest expiry, ignoring missing ones', () => {
    const expiries = [at(3_600_000), undefined, at(600_000), null];

    expect(untilLinkRenewal(expiries, NOW)).toBe(540_000);
  });

  it('returns 0 when no expiry is known or one is already due', () => {
    expect(untilLinkRenewal([], NOW)).toBe(0);
    expect(untilLinkRenewal([undefined, null], NOW)).toBe(0);
    expect(untilLinkRenewal([at(3_600_000), at(30_000)], NOW)).toBe(0);
    expect(untilLinkRenewal([at(-5_000)], NOW)).toBe(0);
    expect(untilLinkRenewal([at(3_600_000), 'not a date'], NOW)).toBe(0);
  });

  it('never waits longer than 24 h', () => {
    expect(untilLinkRenewal([at(10 * 24 * 3_600_000)], NOW)).toBe(24 * 3_600_000);
  });
});
