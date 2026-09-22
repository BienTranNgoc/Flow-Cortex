import { z } from 'zod';

/** Self-service profile update is name-only (design.md D4/R-022). */
export const profileSchema = z.object({
  name: z.string().min(1, 'Name is required'),
});

export type ProfileFormValues = z.infer<typeof profileSchema>;
