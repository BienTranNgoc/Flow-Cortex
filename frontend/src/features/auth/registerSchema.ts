import { z } from 'zod';

export const registerSchema = z
  .object({
    name: z.string().trim().min(1, 'auth.validation.nameRequired'),
    email: z.string().trim().email('auth.validation.emailInvalid'),
    // BCrypt reads at most 72 bytes; the server rejects longer UTF-8 passwords.
    password: z
      .string()
      .min(8, 'auth.validation.passwordTooShort')
      .refine(
        (value) => new TextEncoder().encode(value).length <= 72,
        'auth.validation.passwordTooLong',
      ),
    confirmPassword: z.string().min(1, 'auth.validation.confirmPasswordRequired'),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'auth.validation.passwordsMismatch',
  });

export type RegisterFormValues = z.infer<typeof registerSchema>;
