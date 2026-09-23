import { z } from 'zod';

/** Self-service profile update is name-only. */
export const profileSchema = z.object({
  name: z.string().min(1, 'Name is required'),
});

export type ProfileFormValues = z.infer<typeof profileSchema>;
