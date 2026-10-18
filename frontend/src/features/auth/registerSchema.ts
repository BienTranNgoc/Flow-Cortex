import { z } from 'zod';

const USERNAME = /^[a-z0-9_]{3,20}$/;

/** Mirrors the server's register constraints so a refusal shows at its field before any request. */
export const registerSchema = z
  .object({
    firstName: z
      .string()
      .trim()
      .min(1, 'auth.validation.firstNameRequired')
      .max(100, 'auth.validation.firstNameTooLong'),
    lastName: z
      .string()
      .trim()
      .min(1, 'auth.validation.lastNameRequired')
      .max(100, 'auth.validation.lastNameTooLong'),
    email: z.string().trim().email('auth.validation.emailInvalid'),
    // Empty means "no username"; the server refuses an empty string, so it is omitted on submit.
    username: z
      .string()
      .refine((value) => value === '' || USERNAME.test(value), 'auth.validation.usernameInvalid'),
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

export const resendEmailSchema = z.string().trim().email('auth.validation.emailInvalid');
