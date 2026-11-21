import { creditsUsed, walletTier } from './walletTier';

describe('walletTier', () => {
  it('walks the tiers by the share of what was granted', () => {
    const tiers = [0, 1, 49, 50, 199, 200, 1000].map((balance) => walletTier(balance, 1000));
    expect(tiers).toEqual(['exhausted', 'critical', 'critical', 'low', 'low', 'ok', 'ok']);
  });

  it('is exhausted for a negative balance', () => {
    expect(walletTier(-5, 1000)).toBe('exhausted');
  });

  it('is ok when nothing was granted', () => {
    expect(walletTier(10, 0)).toBe('ok');
  });

  it('counts credits used, never below zero', () => {
    expect(creditsUsed(150, 1000)).toBe(850);
    expect(creditsUsed(1200, 1000)).toBe(0);
  });
});
