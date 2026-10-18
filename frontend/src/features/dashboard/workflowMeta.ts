import type { WorkflowResponse, WorkflowStepResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import i18n from '../../lib/i18n';

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
  [/echo/i, 'dashboard.description.echo'],
  [/fail|deliberate/i, 'dashboard.description.failure'],
  [/douyin|tiktok|repurpose|short/i, 'dashboard.description.repurpose'],
  [/product\s*video|product-video|cart|render/i, 'dashboard.description.productVideo'],
  [/probe|inspect|check a video/i, 'dashboard.description.probe'],
  [/extract|audio/i, 'dashboard.description.extractAudio'],
];

const DEFAULT_ETA = 60;
const DEFAULT_CATEGORY: WorkflowMeta['category'] = 'production';
const DEFAULT_DESCRIPTION = 'dashboard.description.default';

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

function descriptionKeyFor(name: string | undefined): string {
  if (!name) return DEFAULT_DESCRIPTION;
  for (const [pattern, key] of DESCRIPTION_BY_NAME) {
    if (pattern.test(name)) return key;
  }
  return DEFAULT_DESCRIPTION;
}

/** Status comes from the steps alone; the name only drives cosmetic fields (ETA, category, description). */
function statusFor(steps: readonly WorkflowStepResponse[]): {
  labelKey: string;
  tone: StatusTone;
} {
  if (steps.some((step) => step.type === FAILING_TYPE)) {
    return { labelKey: 'dashboard.status.failureDemo', tone: 'attention' };
  }
  if (steps.some((step) => step.type === APPROVAL)) {
    return { labelKey: 'dashboard.status.reviewRequired', tone: 'experimental' };
  }
  return { labelKey: 'dashboard.status.ready', tone: 'ready' };
}

/** Components pass their own `t` so the labels follow a language switch. */
export function metaFor(workflow: WorkflowResponse, t: TFunction = i18n.t): WorkflowMeta {
  const { labelKey, tone } = statusFor(workflow.steps ?? []);
  return {
    statusLabel: t(labelKey),
    statusTone: tone,
    etaSeconds: etaFor(workflow.name),
    category: categoryFor(workflow.name),
    description: t(descriptionKeyFor(workflow.name)),
  };
}

export function stepsFor(workflow: WorkflowResponse, t: TFunction = i18n.t): StepView[] {
  const raw = workflow.steps ?? [];
  return raw.map((step, index) => {
    const id = step.id ?? `${index}-${step.name}`;
    const label = step.name ?? t('dashboard.card.stepFallback', { order: step.stepOrder ?? index + 1 });
    let kind: StepKind = 'normal';
    if (step.type === APPROVAL) kind = 'checkpoint';
    else if (step.type === FAILING_TYPE) kind = 'failure';
    return { id, label, kind };
  });
}
