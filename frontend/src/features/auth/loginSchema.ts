import { z } from 'zod';

/** Matches the backend's own login constraints exactly (@NotBlank only, no @Email). */
export const loginSchema = z.object({
  email: z.string().min(1, 'Email is required'),
  password: z.string().min(1, 'Password is required'),
});

export type LoginFormValues = z.infer<typeof loginSchema>;
