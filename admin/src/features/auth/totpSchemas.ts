import { z } from 'zod';

/** The server's own shape for a TOTP code (spaces allowed) and for a recovery code (spaces and hyphens allowed). */
const TOTP_CODE = /^[0-9 ]{6,7}$/;
const RECOVERY_CODE = /^[A-Za-z2-7 -]{10,13}$/;

/** Enrollment takes only an authenticator code: a recovery code never confirms a new secret. */
export const enrollCodeSchema = z.object({
  code: z
    .string()
    .min(1, 'auth.validation.codeRequired')
    .regex(TOTP_CODE, 'auth.validation.codeShape'),
});

/** Sign-in takes an authenticator code or a recovery code. */
export const signInCodeSchema = z.object({
  code: z
    .string()
    .min(1, 'auth.validation.codeRequired')
    .refine(
      (value) => TOTP_CODE.test(value) || RECOVERY_CODE.test(value),
      'auth.validation.codeShape',
    ),
});

export type CodeFormValues = z.infer<typeof signInCodeSchema>;
