import { z } from 'zod';
import { ROLE_CODES } from './enumLabels';

/** Mirrors `UsersController.CreateUserRequest`. Messages are i18n keys the form translates. */
export const createUserSchema = z.object({
  email: z.string().min(1, 'users.validation.emailRequired').email('users.validation.emailInvalid'),
  password: z
    .string()
    .min(8, 'users.validation.passwordMin')
    .max(72, 'users.validation.passwordMax'),
  name: z.string().min(1, 'users.validation.nameRequired'),
  role: z.enum(ROLE_CODES),
});

export type CreateUserFormValues = z.infer<typeof createUserSchema>;

/**
 * Mirrors `UsersController.UpdateUserRequest` — the backend accepts a
 * partial update; the form validates every field, and the row sends `role` and
 * `status` only when the user changed them.
 */
export const editUserSchema = z.object({
  email: z.string().min(1, 'users.validation.emailRequired').email('users.validation.emailInvalid'),
  name: z.string().min(1, 'users.validation.nameRequired'),
  role: z.enum(ROLE_CODES),
  status: z.enum(['ACTIVE', 'SUSPENDED']),
});

export type EditUserFormValues = z.infer<typeof editUserSchema>;
