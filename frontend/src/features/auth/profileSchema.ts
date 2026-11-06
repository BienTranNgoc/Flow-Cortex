import { z } from 'zod';

/** Self-service profile update is name-only. */
export const profileSchema = z.object({
  name: z.string().min(1, 'auth.validation.nameRequired'),
});

export type ProfileFormValues = z.infer<typeof profileSchema>;
