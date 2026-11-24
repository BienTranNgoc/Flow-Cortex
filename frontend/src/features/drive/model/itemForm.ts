import { z } from 'zod';

export const NAME_MAX = 160;
export const TEXT_MAX = 32_768;

// Both limits count UTF-16 code units, as the server does with String.length().
export const nameSchema = z
  .string()
  .trim()
  .min(1, 'driveItems.validation.nameRequired')
  .max(NAME_MAX, 'driveItems.validation.nameTooLong');

export const textSchema = z
  .string()
  .min(1, 'driveItems.validation.textRequired')
  .max(TEXT_MAX, 'driveItems.validation.textTooLong');

const roleSchema = z.string().min(1, 'driveItems.validation.roleRequired');

export const textItemSchema = z.object({
  name: nameSchema,
  semanticRole: roleSchema,
  textValue: textSchema,
});

export const editItemSchema = z.object({
  name: nameSchema,
  semanticRole: roleSchema,
  textValue: textSchema.optional(),
});

export type TextItemFormValues = z.infer<typeof textItemSchema>;
export type EditItemFormValues = z.infer<typeof editItemSchema>;
