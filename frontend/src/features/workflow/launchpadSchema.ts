import { z } from 'zod';

const CREATOR_LINK = /^https?:\/\/(?:(?:www|vm|vt)\.)?(?:tiktok\.com|douyin\.com)(?:\/|$)/i;

export const launchpadSchema = z.object({
  sourceUrl: z.string().trim().regex(CREATOR_LINK, 'workflow.validation.creatorLink'),
});

export type LaunchpadFormValues = z.infer<typeof launchpadSchema>;
