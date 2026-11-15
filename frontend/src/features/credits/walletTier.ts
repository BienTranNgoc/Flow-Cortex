export type WalletTier = 'ok' | 'low' | 'critical' | 'exhausted';

const CRITICAL_BELOW = 0.05;
const LOW_BELOW = 0.2;

export function walletTier(balance: number, granted: number): WalletTier {
  if (balance <= 0) {
    return 'exhausted';
  }
  if (granted <= 0) {
    return 'ok';
  }
  const share = balance / granted;
  if (share < CRITICAL_BELOW) {
    return 'critical';
  }
  return share < LOW_BELOW ? 'low' : 'ok';
}

export function creditsUsed(balance: number, granted: number): number {
  return Math.max(0, granted - balance);
}
