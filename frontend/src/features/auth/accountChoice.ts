/**
 * The value to apply on this device after the account's preferences load, or `undefined` when the
 * device keeps what it has: the account has nothing saved, holds a value this build does not know,
 * or already agrees with the device.
 */
export function accountChoiceToApply<T extends string>(
  account: string | null | undefined,
  device: T,
  allowed: readonly T[],
): T | undefined {
  const known = allowed.find((value) => value === account);
  if (known === undefined || known === device) return undefined;
  return known;
}
