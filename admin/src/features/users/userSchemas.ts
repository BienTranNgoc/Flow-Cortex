import { z } from 'zod';
import { ROLE_CODE_MAX_LENGTH } from './enumLabels';

/** Any role code the server may hold, system or custom; the server decides whether the code exists. */
export const roleCodeSchema = z
  .string()
  .min(1, 'users.detail.invalidRole')
  .max(ROLE_CODE_MAX_LENGTH, 'users.detail.invalidRole');

/** Mirrors `UsersController.CreateUserRequest`. Messages are i18n keys the form translates. */
export const createUserSchema = z.object({
  email: z.string().min(1, 'users.validation.emailRequired').email('users.validation.emailInvalid'),
  password: z
    .string()
    .min(8, 'users.validation.passwordMin')
    .max(72, 'users.validation.passwordMax'),
  name: z.string().min(1, 'users.validation.nameRequired'),
  role: roleCodeSchema,
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
  role: roleCodeSchema,
  status: z.enum(['ACTIVE', 'SUSPENDED']),
});

export type EditUserFormValues = z.infer<typeof editUserSchema>;
