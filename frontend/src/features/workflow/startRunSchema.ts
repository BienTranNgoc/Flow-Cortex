import { z } from 'zod';

/**
 * Mirrors `WorkflowsController.StartRunRequest` (design.md D5/R-042, task
 * 5.10) — `input` is optional: not every workflow needs one, and the seeded
 * `echo` demo just returns whatever is given, including nothing.
 */
export const startRunSchema = z.object({
  input: z.string().optional(),
});

export type StartRunFormValues = z.infer<typeof startRunSchema>;
