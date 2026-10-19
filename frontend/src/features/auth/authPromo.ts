/** Rows render in order: the first with a calendar icon, the second with a clock. */
export interface AuthPromoRow {
  label: string;
  value: string;
  note?: string;
}

export interface AuthPromo {
  tag: string;
  big: string;
  sub: string;
  rows: readonly AuthPromoRow[];
}

/**
 * The banner promo; `null` shows nothing in its place. A campaign the backend actually grants
 * turns it on with `authCopy.promo`.
 */
export const authPromo: AuthPromo | null = null;
