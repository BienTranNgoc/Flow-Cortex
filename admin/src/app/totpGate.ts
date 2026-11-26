import type { MeResponse } from '@flow-cortex/api-client';

export type TotpGate = 'setup' | 'signOut' | 'enter';

/**
 * What the console does with a signed-in account before any admin page mounts: an account that must
 * have a second factor and has none enrolls first; one that has it but whose session never passed a
 * code signs in again through the code step.
 */
export function totpGate({ totp }: Pick<MeResponse, 'totp'>): TotpGate {
  if (!totp.required) return 'enter';
  if (!totp.enabled) return 'setup';
  return totp.verified ? 'enter' : 'signOut';
}
