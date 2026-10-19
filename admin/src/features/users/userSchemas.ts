import { z } from 'zod';

/** Mirrors `UsersController.CreateUserRequest`. Messages are i18n keys the form translates. */
export const createUserSchema = z.object({
  email: z.string().min(1, 'users.validation.emailRequired').email('users.validation.emailInvalid'),
  password: z
    .string()
    .min(8, 'users.validation.passwordMin')
    .max(72, 'users.validation.passwordMax'),
  name: z.string().min(1, 'users.validation.nameRequired'),
  role: z.enum(['ADMIN', 'MEMBER']),
});

export type CreateUserFormValues = z.infer<typeof createUserSchema>;

/**
 * Mirrors `UsersController.UpdateUserRequest` — the backend accepts a
 * partial update, but this form always submits the current+edited value of
 * every field, so every field here is required from the form's own point of
 * view.
 */
export const editUserSchema = z.object({
  email: z.string().min(1, 'users.validation.emailRequired').email('users.validation.emailInvalid'),
  name: z.string().min(1, 'users.validation.nameRequired'),
  role: z.enum(['ADMIN', 'MEMBER', 'CREATOR']),
  status: z.enum(['ACTIVE', 'SUSPENDED']),
});

export type EditUserFormValues = z.infer<typeof editUserSchema>;
