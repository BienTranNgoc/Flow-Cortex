import { z } from 'zod';

export const registerSchema = z
  .object({
    name: z.string().trim().min(1, 'Name is required'),
    email: z.string().trim().email('Enter a valid email address'),
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
