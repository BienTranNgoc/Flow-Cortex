import { z } from 'zod';

/** Matches the backend's own login constraints exactly (@NotBlank only, no @Email). */
export const loginSchema = z.object({
  email: z.string().min(1, 'auth.validation.emailRequired'),
  password: z.string().min(1, 'auth.validation.passwordRequired'),
});

export type LoginFormValues = z.infer<typeof loginSchema>;
