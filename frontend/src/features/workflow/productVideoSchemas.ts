import type { RunResponse, RunStepOutputResponse, RunStepResponse } from '@flow-cortex/api-client';
import { z } from 'zod';

export const PRODUCT_VIDEO_MC_WORKFLOW_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14302';

export const ProductBriefSchema = z.object({
  productName: z.string().trim().min(1),
  description: z.string().trim().min(1),
});

export type ProductBrief = z.infer<typeof ProductBriefSchema>;

export const StoryboardOutputSchema = z.object({
  profile: z.string().optional(),
  sourceRevisionId: z.string().optional(),
  scene1Prompt: z.string().trim().min(1),
  scene2Prompt: z.string().trim().min(1),
});

export type StoryboardOutput = z.infer<typeof StoryboardOutputSchema>;

export const SpeechOutputSchema = z.object({
  profile: z.string().optional(),
  durationMs: z.number().finite().positive().optional(),
  provider: z.string().optional(),
  voice: z.string().optional(),
  speed: z.string().optional(),
  format: z.string().optional(),
});

export type SpeechOutput = z.infer<typeof SpeechOutputSchema>;

export const VideoOutputSchema = z.object({
  profile: z.string().optional(),
  aspectRatio: z.string().optional(),
  durationSeconds: z.number().finite().positive().optional(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
  resolution: z.string().optional(),
});

export type VideoOutput = z.infer<typeof VideoOutputSchema>;

function parseJsonString(raw: string | undefined): unknown {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

/** Parses the JSON product brief stored on a product-video run's input field. */
export function parseProductBrief(rawInput: string | undefined): ProductBrief | null {
  const parsed = ProductBriefSchema.safeParse(parseJsonString(rawInput));
  return parsed.success ? parsed.data : null;
}

/** Parses the structured two-scene prompt payload emitted by `generate_storyboard`. */
export function parseStoryboardOutput(rawOutput: string | undefined): StoryboardOutput | null {
  const parsed = StoryboardOutputSchema.safeParse(parseJsonString(rawOutput));
  return parsed.success ? parsed.data : null;
}

/** Parses metadata emitted by `generate_speech`, returning null when no speech fields are present. */
export function parseSpeechOutput(rawOutput: string | undefined): SpeechOutput | null {
  const parsed = SpeechOutputSchema.safeParse(parseJsonString(rawOutput));
  if (!parsed.success) return null;
  const d = parsed.data;
  if (d.durationMs === undefined && d.voice === undefined && d.format === undefined) return null;
  return d;
}

/** Parses metadata emitted by `generate_video` or `render_video`. */
export function parseVideoOutput(rawOutput: string | undefined): VideoOutput | null {
  const parsed = VideoOutputSchema.safeParse(parseJsonString(rawOutput));
  if (!parsed.success) return null;
  const d = parsed.data;
  if (
    d.aspectRatio === undefined &&
    d.durationSeconds === undefined &&
    d.width === undefined &&
    d.height === undefined &&
    d.resolution === undefined
  ) {
    return null;
  }
  return d;
}

/**
 * A run is a Product Video run only when its workflow is the one the capabilities name; step types
 * and the brief's shape are not exclusive to it. Until that id is known, no run is.
 */
export function isProductVideoRun(
  run: Pick<RunResponse, 'workflowId'> | undefined,
  productVideoWorkflowId: string | undefined,
): boolean {
  if (!run?.workflowId) return false;
  return (
    run.workflowId === PRODUCT_VIDEO_MC_WORKFLOW_ID ||
    (productVideoWorkflowId !== undefined && run.workflowId === productVideoWorkflowId)
  );
}

export function isMcProductVideoRun(
  run: Pick<RunResponse, 'workflowId'> | undefined,
): boolean {
  return run?.workflowId === PRODUCT_VIDEO_MC_WORKFLOW_ID;
}

/** Returns the completed `render_video` step and its downloadable final MP4 artifact, if ready. */
export function findFinalVideoArtifact(
  steps: readonly RunStepResponse[],
): { step: RunStepResponse; artifact: RunStepOutputResponse } | null {
  const renderStep = steps.find(
    (step) => step.type === 'render_video' && step.status === 'completed',
  );
  if (renderStep?.stepOrder === undefined) return null;
  const artifact = (renderStep.outputs ?? []).find(
    (output) => output.mime?.startsWith('video/') || output.name.endsWith('.mp4'),
  );
  if (!artifact) return null;
  return { step: renderStep, artifact };
}
