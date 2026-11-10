export type SettingInput =
  | { readonly ok: true; readonly value: number }
  | { readonly ok: false; readonly min: number; readonly max: number };

const WHOLE_NUMBER = /^\d+$/;

/** A whole number of plain digits inside `[min, max]`; anything else is refused with the bounds it broke. */
export function parseSettingInput(raw: string, min: number, max: number): SettingInput {
  const text = raw.trim();
  if (WHOLE_NUMBER.test(text)) {
    const value = Number(text);
    if (value >= min && value <= max) return { ok: true, value };
  }
  return { ok: false, min, max };
}
