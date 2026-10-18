import { z } from 'zod';

const USERNAME = /^[a-z0-9_]{3,20}$/;

// BCrypt reads at most 72 bytes; the server rejects longer UTF-8 passwords.
const password = z
  .string()
  .min(8, 'auth.validation.passwordTooShort')
  .refine(
    (value) => new TextEncoder().encode(value).length <= 72,
    'auth.validation.passwordTooLong',
  );

const confirmPassword = z.string().min(1, 'auth.validation.confirmPasswordRequired');

const passwordsMatch = {
  path: ['confirmPassword'],
  message: 'auth.validation.passwordsMismatch',
};

const firstName = z
  .string()
  .trim()
  .min(1, 'auth.validation.firstNameRequired')
  .max(100, 'auth.validation.firstNameTooLong');
const lastName = z
  .string()
  .trim()
  .min(1, 'auth.validation.lastNameRequired')
  .max(100, 'auth.validation.lastNameTooLong');
// Empty means "no username"; the server refuses an empty string, so it is omitted on submit.
const username = z
  .string()
  .refine((value) => value === '' || USERNAME.test(value), 'auth.validation.usernameInvalid');

/** Mirrors the server's register constraints so a refusal shows at its field before any request. */
export const registerSchema = z
  .object({
    firstName,
    lastName,
    email: z.string().trim().email('auth.validation.emailInvalid'),
    username,
    password,
    confirmPassword,
  })
  .refine((values) => values.password === values.confirmPassword, passwordsMatch);

export type RegisterFormValues = z.infer<typeof registerSchema>;

/** The registration rules, for the password, names and username chosen once the email is proven. */
export const setPasswordSchema = z
  .object({ firstName, lastName, username, password, confirmPassword })
  .refine((values) => values.password === values.confirmPassword, passwordsMatch);

export type SetPasswordFormValues = z.infer<typeof setPasswordSchema>;

export const resendEmailSchema = z.string().trim().email('auth.validation.emailInvalid');
