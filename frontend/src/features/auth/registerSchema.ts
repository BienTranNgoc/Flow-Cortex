import { z } from 'zod';
import { authCopy } from './authCopy';

const USERNAME = /^[a-z0-9_]{3,20}$/;
const message = authCopy.register.validation;

/** Mirrors the server's register constraints so a refusal shows at its field before any request. */
export const registerSchema = z
  .object({
    firstName: z
      .string()
      .trim()
      .min(1, message.firstNameRequired)
      .max(100, message.firstNameTooLong),
    lastName: z.string().trim().min(1, message.lastNameRequired).max(100, message.lastNameTooLong),
    email: z.string().trim().email(message.emailInvalid),
    // Empty means "no username"; the server refuses an empty string, so it is omitted on submit.
    username: z
      .string()
      .refine((value) => value === '' || USERNAME.test(value), message.usernameFormat),
    // BCrypt reads at most 72 bytes; the server rejects longer UTF-8 passwords.
    password: z
      .string()
      .min(8, message.passwordTooShort)
      .refine((value) => new TextEncoder().encode(value).length <= 72, message.passwordTooLong),
    confirmPassword: z.string().min(1, message.confirmRequired),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: message.passwordMismatch,
  });

export type RegisterFormValues = z.infer<typeof registerSchema>;

export const resendEmailSchema = z.string().trim().email(authCopy.resend.invalidEmail);
