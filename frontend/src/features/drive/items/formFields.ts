import type { TFunction } from 'i18next';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { formatDateTime } from '../../../lib/format';
import type { Locale } from '../../../lib/i18n';
import { editItemSchema } from '../model/itemForm';

/** The name and role every save of a file-backed item asks for. */
export const nameRoleSchema = editItemSchema.pick({ name: true, semanticRole: true });
export type NameRoleValues = { name: string; semanticRole: string };

/** A field's message: a translation key from the zod schema, or the server's sentence as written. */
export function fieldMessage(
  error: { type?: string | number; message?: string } | undefined,
  t: TFunction,
): string | undefined {
  if (!error?.message) return undefined;
  return error.type === 'server' ? error.message : t(error.message);
}

/** Puts each server sentence under its field and focuses the first one; the form keeps what was typed. */
export function showFieldRefusals<T extends FieldValues>(
  byField: Record<string, string>,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
) {
  let focused = false;
  for (const field of fields) {
    const message = byField[field];
    if (message) {
      setError(field, { type: 'server', message }, { shouldFocus: !focused });
      focused = true;
    }
  }
}

/** A card's date: day and time in the member's language. */
export function formatWhen(value: string, locale: Locale): string {
  return formatDateTime(value, locale, { dateStyle: 'medium', timeStyle: 'short' });
}
