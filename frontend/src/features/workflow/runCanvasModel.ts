import type { RunResponse, RunStepResponse } from '@flow-cortex/api-client';
import {
  RUN_INPUT_KEY,
  edgeState,
  stepKey,
  stepState,
  type EdgeState,
  type StepState,
} from './stepDisplay';
import { isLiveRunStatus } from './useRunEvents';

export const NODE_PITCH = 340;
export const NODE_WIDTH = 240;
export const FIT_PADDING = 0.12;
export const READABLE_FIT = 0.67;
export const MIN_ZOOM = 0.2;
export const MAX_ZOOM = 1.5;
export const SPLIT_MIN = 30;
export const SPLIT_MAX = 75;
export const SPLIT_DEFAULT = 40;
export const SPLIT_STEP = 2;
export const INPUT_PREVIEW = 60;
export const INPUT_NODE_ID = RUN_INPUT_KEY;

/** Gap kept between a node panned into view and the canvas edge, so its focus ring is not clipped. */
const FOCUS_MARGIN = 16;
/** Absorbs the rounding of a fit that is mathematically exactly the readable floor. */
const FIT_EPSILON = 1e-9;

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Size {
  width: number;
  height: number;
}

export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export interface CanvasNode {
  id: string;
  kind: 'input' | 'step';
  position: { x: number; y: number };
  width: number;
  step?: RunStepResponse;
}

export interface CanvasEdge {
  id: string;
  source: string;
  target: string;
  state: EdgeState;
}

export interface CanvasGraph {
  nodes: CanvasNode[];
  edges: CanvasEdge[];
}

export type InputSummary =
  | { kind: 'file'; text: string }
  | { kind: 'text'; text: string; title: string }
  | { kind: 'label' };

export interface RunProgress {
  current: number;
  total: number;
}

/** The steps by ascending `stepOrder`: the one ordering canvas nodes, progress and positions use. */
export function orderedSteps(steps: readonly RunStepResponse[] | undefined): RunStepResponse[] {
  return [...(steps ?? [])].sort((a, b) => (a.stepOrder ?? 0) - (b.stepOrder ?? 0));
}

/**
 * Runs written before uploads existed may hold a JSON reference as their input; that is never a
 * file name, so it is not shown.
 */
export function looksLikeJson(value: string): boolean {
  try {
    const parsed: unknown = JSON.parse(value);
    return typeof parsed === 'object' && parsed !== null;
  } catch {
    return false;
  }
}

export function inputEdgeState(first: RunStepResponse): EdgeState {
  if (first.status === 'completed') {
    return 'done';
  }
  return first.status === 'running' ? 'active' : 'pending';
}

/** The Input node, then the steps by ascending order at one pitch each, joined in a chain. */
export function runCanvasGraph(run: RunResponse): CanvasGraph {
  const steps = orderedSteps(run.steps);
  const first = steps[0];
  if (!first) {
    return { nodes: [], edges: [] };
  }
  const nodes: CanvasNode[] = [
    { id: INPUT_NODE_ID, kind: 'input', position: { x: 0, y: 0 }, width: NODE_WIDTH },
    ...steps.map((step, index) => ({
      id: stepKey(step),
      kind: 'step' as const,
      position: { x: (index + 1) * NODE_PITCH, y: 0 },
      width: NODE_WIDTH,
      step,
    })),
  ];
  const edges: CanvasEdge[] = [
    {
      id: `${INPUT_NODE_ID}->${stepKey(first)}`,
      source: INPUT_NODE_ID,
      target: stepKey(first),
      state: inputEdgeState(first),
    },
    ...steps.slice(1).map((step, index) => {
      const from = steps[index]!;
      return {
        id: `${stepKey(from)}->${stepKey(step)}`,
        source: stepKey(from),
        target: stepKey(step),
        state: edgeState(from, step),
      };
    }),
  ];
  return { nodes, edges };
}

export function inputSummary(run: RunResponse): InputSummary {
  const input = run.input;
  if (!input || input.trim() === '' || looksLikeJson(input)) {
    return { kind: 'label' };
  }
  if (orderedSteps(run.steps)[0]?.mode === 'remote') {
    return { kind: 'file', text: input };
  }
  const characters = Array.from(input);
  return characters.length > INPUT_PREVIEW
    ? { kind: 'text', text: `${characters.slice(0, INPUT_PREVIEW).join('')}…`, title: input }
    : { kind: 'text', text: input, title: input };
}

const IN_PROGRESS: ReadonlySet<StepState> = new Set(['waiting', 'running', 'needs-you']);

/** "Step X of N" while the run is live: X is the 1-based position of the step in progress. */
export function runProgress(
  steps: readonly RunStepResponse[],
  status: string | undefined,
): RunProgress | null {
  if (!isLiveRunStatus(status) || steps.length === 0) {
    return null;
  }
  const ordered = orderedSteps(steps);
  const inProgress = ordered.findIndex((step) => IN_PROGRESS.has(stepState(step, ordered, status)));
  if (inProgress >= 0) {
    return { current: inProgress + 1, total: ordered.length };
  }
  const completed = ordered.filter((step) => step.status === 'completed').length;
  return { current: Math.min(completed + 1, ordered.length), total: ordered.length };
}

/** Whole seconds since a worker picked the step up, never negative; null without a usable time. */
export function elapsedSeconds(pickedUpAt: string | undefined, nowMs: number): number | null {
  if (!pickedUpAt) {
    return null;
  }
  const pickedUp = new Date(pickedUpAt).getTime();
  if (Number.isNaN(pickedUp)) {
    return null;
  }
  return Math.max(0, Math.floor((nowMs - pickedUp) / 1000));
}

/**
 * First framing: fit the whole graph when that stays readable, else 100% centred on the focus
 * node. Boxes are in flow coordinates; the view maps them to canvas pixels.
 */
export function initialView(graphBox: Box, canvasBox: Size, focusBox: Box): Viewport {
  const padded = 1 + 2 * FIT_PADDING;
  const usable = canvasBox.width > 0 && canvasBox.height > 0 && graphBox.width > 0;
  const fit = usable
    ? Math.min(
        canvasBox.width / (graphBox.width * padded),
        graphBox.height > 0 ? canvasBox.height / (graphBox.height * padded) : 1,
        1,
      )
    : 0;
  if (fit + FIT_EPSILON >= READABLE_FIT) {
    return {
      zoom: fit,
      x: canvasBox.width / 2 - (graphBox.x + graphBox.width / 2) * fit,
      y: canvasBox.height / 2 - (graphBox.y + graphBox.height / 2) * fit,
    };
  }
  return {
    zoom: 1,
    x: canvasBox.width / 2 - (focusBox.x + focusBox.width / 2),
    y: canvasBox.height / 2 - (focusBox.y + focusBox.height / 2),
  };
}

/** Whether a selection the page changed pulls the view: always for a step that needs the member. */
export function shouldFollow(state: StepState, memberMoved: boolean): boolean {
  return state === 'needs-you' || !memberMoved;
}

function offsetIntoView(start: number, length: number, offset: number, extent: number): number {
  const low = start + offset;
  const high = low + length;
  if (low >= 0 && high <= extent) {
    return offset;
  }
  if (low < 0 || length + 2 * FOCUS_MARGIN > extent) {
    return offset + FOCUS_MARGIN - low;
  }
  return offset + extent - FOCUS_MARGIN - high;
}

/** The viewport shifted just far enough to show the node whole, at the same zoom. */
export function panIntoView(nodeBox: Box, viewport: Viewport, canvasBox: Size): Viewport {
  const x = offsetIntoView(
    nodeBox.x * viewport.zoom,
    nodeBox.width * viewport.zoom,
    viewport.x,
    canvasBox.width,
  );
  const y = offsetIntoView(
    nodeBox.y * viewport.zoom,
    nodeBox.height * viewport.zoom,
    viewport.y,
    canvasBox.height,
  );
  return x === viewport.x && y === viewport.y ? viewport : { x, y, zoom: viewport.zoom };
}

export function clampSplit(percent: number): number {
  if (!Number.isFinite(percent)) {
    return SPLIT_DEFAULT;
  }
  return Math.min(SPLIT_MAX, Math.max(SPLIT_MIN, Math.round(percent)));
}

/** The stored canvas percent: clamped, and the default when absent or not a number. */
export function readSplit(raw: string | null): number {
  if (raw === null || raw.trim() === '') {
    return SPLIT_DEFAULT;
  }
  return clampSplit(Number(raw));
}

/** The canvas percent after a key on the split handle; any other key leaves it unchanged. */
export function splitKey(percent: number, key: string): number {
  switch (key) {
    case 'ArrowLeft':
      return clampSplit(percent - SPLIT_STEP);
    case 'ArrowRight':
      return clampSplit(percent + SPLIT_STEP);
    case 'Home':
      return SPLIT_MIN;
    case 'End':
      return SPLIT_MAX;
    case 'Enter':
      return SPLIT_DEFAULT;
    default:
      return percent;
  }
}
