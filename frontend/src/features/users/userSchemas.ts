import { z } from 'zod';

/** Mirrors `UsersController.CreateUserRequest` (design.md D3, task 4.5). */
export const createUserSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .max(72, 'Password must be at most 72 characters'),
  name: z.string().min(1, 'Name is required'),
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
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  name: z.string().min(1, 'Name is required'),
  role: z.enum(['ADMIN', 'MEMBER']),
  status: z.enum(['ACTIVE', 'SUSPENDED']),
});

export type EditUserFormValues = z.infer<typeof editUserSchema>;
