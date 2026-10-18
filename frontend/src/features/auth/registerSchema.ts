import { z } from 'zod';

const USERNAME = /^[a-z0-9_]{3,20}$/;

/** Mirrors the server's register constraints so a refusal shows at its field before any request. */
export const registerSchema = z
  .object({
    firstName: z
      .string()
      .trim()
      .min(1, 'First name is required')
      .max(100, 'First name is too long'),
    lastName: z.string().trim().min(1, 'Last name is required').max(100, 'Last name is too long'),
    email: z.string().trim().email('Enter a valid email address'),
    // Empty means "no username"; the server refuses an empty string, so it is omitted on submit.
    username: z
      .string()
      .refine(
        (value) => value === '' || USERNAME.test(value),
        'Use 3–20 lowercase letters, digits or _',
      ),
    // BCrypt reads at most 72 bytes; the server rejects longer UTF-8 passwords.
    password: z
      .string()
      .min(8, 'Password must be at least 8 characters')
      .refine((value) => new TextEncoder().encode(value).length <= 72, 'Password is too long'),
    confirmPassword: z.string().min(1, 'Confirm your password'),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match',
  });

export type RegisterFormValues = z.infer<typeof registerSchema>;

export const resendEmailSchema = z.string().trim().email('Enter a valid email address');
