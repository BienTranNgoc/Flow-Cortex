import { z } from 'zod';

const optionalPart = (tooLongKey: string) => z.string().trim().max(100, tooLongKey);

/** Names a member may edit about themself; first and last name are optional. */
export const profileSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, 'auth.validation.nameRequired')
    .max(255, 'auth.validation.nameTooLong'),
  firstName: optionalPart('auth.validation.firstNameTooLong'),
  lastName: optionalPart('auth.validation.lastNameTooLong'),
});

export type ProfileFormValues = z.infer<typeof profileSchema>;

/** Same shape as the registration username: 3-20 lowercase letters, digits or underscore. */
export const usernameSchema = z
  .string()
  .regex(/^[a-z0-9_]{3,20}$/, 'auth.validation.usernameInvalid');
