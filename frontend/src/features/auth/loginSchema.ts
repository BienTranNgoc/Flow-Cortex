import { z } from 'zod';
import { authCopy } from './authCopy';

/** Matches the backend's own login constraints exactly (@NotBlank only, no @Email). */
export const loginSchema = z.object({
  email: z.string().min(1, authCopy.login.emailRequired),
  password: z.string().min(1, authCopy.login.passwordRequired),
  rememberMe: z.boolean(),
});

export type LoginFormValues = z.infer<typeof loginSchema>;
