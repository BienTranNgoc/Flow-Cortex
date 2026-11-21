// Mirror of com.flowcortex.onboarding.domain.OnboardingVocabulary (backend is the source of truth).
export const PURPOSES = [
  'marketing_content',
  'automation',
  'documents_reports',
  'customer_care',
  'research',
  'other',
] as const;
export const SOURCES = ['facebook', 'tiktok', 'linkedin', 'google', 'referral', 'other'] as const;
export const AI_LEVELS = ['beginner', 'tried', 'automation', 'builder'] as const;
export const INDUSTRIES = ['marketing', 'sales', 'finance', 'hr', 'tech', 'other'] as const;
export const CONTENT_TYPES = [
  'text',
  'image',
  'audio',
  'video',
  'spreadsheet',
  'document',
  'slide',
] as const;
export const USAGE_TYPES = ['personal', 'team'] as const;
export const PROGRESS_STEPS = ['welcome', 'tour:home', 'survey-intro', 'quiz', 'done'] as const;

export type Purpose = (typeof PURPOSES)[number];
export type Source = (typeof SOURCES)[number];
export type AiLevel = (typeof AI_LEVELS)[number];
export type Industry = (typeof INDUSTRIES)[number];
export type ContentType = (typeof CONTENT_TYPES)[number];
export type UsageType = (typeof USAGE_TYPES)[number];
export type ProgressStep = (typeof PROGRESS_STEPS)[number];

export const QUESTION_COUNT = 6;
export type QuizQuestion = 1 | 2 | 3 | 4 | 5 | 6;

export function isQuizQuestion(value: unknown): value is QuizQuestion {
  return Number.isInteger(value) && (value as number) >= 1 && (value as number) <= QUESTION_COUNT;
}
