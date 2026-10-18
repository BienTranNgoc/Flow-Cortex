import type { WorkflowResponse, WorkflowStepResponse } from '@flow-cortex/api-client';

export type StatusTone = 'ready' | 'experimental' | 'attention';
export type StepKind = 'normal' | 'checkpoint' | 'failure';

export interface StepView {
  readonly id: string;
  readonly label: string;
  readonly kind: StepKind;
}

export interface WorkflowMeta {
  readonly statusLabel: string;
  readonly statusTone: StatusTone;
  readonly etaSeconds: number;
  readonly category: 'video' | 'audio' | 'demo' | 'production';
  readonly description: string;
}

const APPROVAL = 'APPROVAL_CHECKPOINT';
const FAILING_TYPE = 'fail';

const ETA_BY_NAME: Array<[RegExp, number]> = [
  [/tiktok|douyin|short|repurpose/i, 90],
  [/echo/i, 30],
  [/fail|deliberate/i, 45],
  [/product\s*video|product-video|cart/i, 180],
  [/video|render|extract|probe/i, 75],
  [/audio|sound|tts|speech/i, 60],
];

const CATEGORY_BY_NAME: Array<[RegExp, WorkflowMeta['category']]> = [
  [/audio|sound|tts|speech/i, 'audio'],
  [/echo|fail|deliberate|demo/i, 'demo'],
  [/product|cart|render|advert/i, 'production'],
  [/video|douyin|tiktok|short/i, 'video'],
];

/** One short sentence for each known workflow, in language a non-engineer can act on. */
const DESCRIPTION_BY_NAME: Array<[RegExp, string]> = [
  [/echo/i, 'Try a simple two-step pipeline that just echoes your input back. Great for learning how runs work.'],
  [/fail|deliberate/i, 'Run a workflow that fails on purpose so you can see how errors look and get reported.'],
  [/douyin|tiktok|repurpose|short/i, 'Take a long video and turn it into a short clip sized for social feeds.'],
  [/product\s*video|product-video|cart|render/i, 'Generate a product video end-to-end: script, voice, and final render.'],
  [/probe|inspect|check a video/i, 'Upload a media file and get a quick technical report (length, size, codecs).'],
  [/extract|audio/i, 'Pull the audio track out of a video and save it as a separate file.'],
];

const DEFAULT_ETA = 60;
const DEFAULT_CATEGORY: WorkflowMeta['category'] = 'production';
const DEFAULT_DESCRIPTION =
  'Run this pipeline with your own input and download the result when it finishes.';

function etaFor(name: string | undefined): number {
  if (!name) return DEFAULT_ETA;
  for (const [pattern, seconds] of ETA_BY_NAME) {
    if (pattern.test(name)) return seconds;
  }
  return DEFAULT_ETA;
}

function categoryFor(name: string | undefined): WorkflowMeta['category'] {
  if (!name) return DEFAULT_CATEGORY;
  for (const [pattern, value] of CATEGORY_BY_NAME) {
    if (pattern.test(name)) return value;
  }
  return DEFAULT_CATEGORY;
}

function descriptionFor(name: string | undefined): string {
  if (!name) return DEFAULT_DESCRIPTION;
  for (const [pattern, text] of DESCRIPTION_BY_NAME) {
    if (pattern.test(name)) return text;
  }
  return DEFAULT_DESCRIPTION;
}

/** Status comes from the steps alone; the name only drives cosmetic fields (ETA, category, description). */
function statusFor(steps: readonly WorkflowStepResponse[]): {
  label: string;
  tone: StatusTone;
} {
  if (steps.some((step) => step.type === FAILING_TYPE)) {
    return { label: 'Demo: failure', tone: 'attention' };
  }
  if (steps.some((step) => step.type === APPROVAL)) {
    return { label: 'Review required', tone: 'experimental' };
  }
  return { label: 'Ready', tone: 'ready' };
}

export function metaFor(workflow: WorkflowResponse): WorkflowMeta {
  const { label, tone } = statusFor(workflow.steps ?? []);
  return {
    statusLabel: label,
    statusTone: tone,
    etaSeconds: etaFor(workflow.name),
    category: categoryFor(workflow.name),
    description: descriptionFor(workflow.name),
  };
}

export function stepsFor(workflow: WorkflowResponse): StepView[] {
  const raw = workflow.steps ?? [];
  return raw.map((step, index) => {
    const id = step.id ?? `${index}-${step.name}`;
    const label = step.name ?? `Step ${step.stepOrder ?? index + 1}`;
    let kind: StepKind = 'normal';
    if (step.type === APPROVAL) kind = 'checkpoint';
    else if (step.type === FAILING_TYPE) kind = 'failure';
    return { id, label, kind };
  });
}
