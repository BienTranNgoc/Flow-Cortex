import type { RunResponse, RunStepOutputResponse, RunStepResponse } from '@flow-cortex/api-client';
import { looksLikeJson, orderedSteps, runProgress } from './runCanvasModel';
import { parseStoryboardOutput, type StoryboardOutput } from './productVideoSchemas';
import { stepKey, type StepState } from './stepDisplay';
import { parseReport } from './stepOutputFields';

export const TABLE_PARSE_LIMIT = 200_000;
export const TABLE_ROW_LIMIT = 500;
export const ITEM_TEXT_LIMIT = 200;
export const SAVED_NOTE_MS = 30_000;
export const LONG_RUN_MS = 300_000;
export const VERY_LONG_RUN_MS = 600_000;

export type TableCell = string | number | boolean | null;
export type TableRow = Record<string, TableCell>;

export interface SortedRow {
  /** Position in the output, kept as the row's key whatever the sort. */
  index: number;
  row: TableRow;
}

export type SortDirection = 'asc' | 'desc';

export interface FileCounts {
  video: number;
  audio: number;
  image: number;
  file: number;
}

export type Outcome =
  | { kind: 'storyboard'; storyboard: StoryboardOutput }
  | { kind: 'iterate' }
  | { kind: 'table'; rows: TableRow[]; columns: string[] }
  | { kind: 'report' }
  | { kind: 'text'; text: string }
  | { kind: 'files'; counts: FileCounts }
  | { kind: 'details' }
  | { kind: 'empty' };

const JSON_MIME = 'application/json';

const isJsonOutput = (output: RunStepOutputResponse) => output.mime === JSON_MIME;

function isCell(value: unknown): value is TableCell {
  return value === null || ['string', 'number', 'boolean'].includes(typeof value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** The rows of table-shaped output text, else null: see `isTableShaped`. */
function parseTableRows(text: string): TableRow[] | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith('[') || trimmed.length >= TABLE_PARSE_LIMIT) {
    return null;
  }
  const parsed = parseJson(trimmed);
  if (!Array.isArray(parsed) || parsed.length === 0) {
    return null;
  }
  const rows: TableRow[] = [];
  for (const item of parsed) {
    if (!isRecord(item) || !Object.values(item).every(isCell)) {
      return null;
    }
    rows.push(item as TableRow);
  }
  const firstKeys = Object.keys(rows[0]!);
  if (!firstKeys.some((key) => rows.every((row) => key in row))) {
    return null;
  }
  return rows;
}

/** A non-empty list of flat objects of which some key of the first is in every one. */
export function isTableShaped(text: string): boolean {
  return parseTableRows(text) !== null;
}

/** Every key across the rows, in first-seen order. */
export function tableColumns(rows: readonly TableRow[]): string[] {
  const seen = new Set<string>();
  for (const row of rows) {
    for (const key of Object.keys(row)) {
      seen.add(key);
    }
  }
  return [...seen];
}

const isEmptyCell = (cell: TableCell | undefined): cell is null | undefined =>
  cell === null || cell === undefined;

function compareCells(a: TableCell, b: TableCell, collator: Intl.Collator): number {
  if (typeof a === 'number' && typeof b === 'number') {
    return a - b;
  }
  return collator.compare(String(a), String(b));
}

/**
 * The rows with their original index, sorted by one column: numbers numerically, anything else by
 * the collator, empty cells last in both directions. Without a direction the order is the output's.
 */
export function sortRows(
  rows: readonly TableRow[],
  column: string,
  direction: SortDirection | null,
  collator: Intl.Collator,
): SortedRow[] {
  const indexed = rows.map((row, index) => ({ index, row }));
  if (direction === null) {
    return indexed;
  }
  const sign = direction === 'asc' ? 1 : -1;
  return indexed.sort((a, b) => {
    const left = a.row[column];
    const right = b.row[column];
    if (isEmptyCell(left) || isEmptyCell(right)) {
      return Number(isEmptyCell(left)) - Number(isEmptyCell(right)) || a.index - b.index;
    }
    return sign * compareCells(left, right, collator) || a.index - b.index;
  });
}

/** The first `TABLE_ROW_LIMIT` rows and how many there are. */
export function visibleRows(rows: readonly SortedRow[]): { rows: SortedRow[]; total: number } {
  return { rows: rows.slice(0, TABLE_ROW_LIMIT), total: rows.length };
}

/** Length in Unicode code points. */
export function codePointLength(text: string): number {
  let count = 0;
  for (const _ of text) {
    count += 1;
  }
  return count;
}

const IMAGE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp'];
const AUDIO_EXTENSIONS = ['.wav', '.mp3'];

type FileKind = keyof FileCounts;

/** The same tests as the stored-output viewer applies, so a counted kind is the viewer it gets. */
function fileKind(output: RunStepOutputResponse): FileKind {
  const { name } = output;
  if (output.mime?.startsWith('video/') || name.endsWith('.mp4')) return 'video';
  if (output.mime?.startsWith('audio/') || AUDIO_EXTENSIONS.some((ext) => name.endsWith(ext))) {
    return 'audio';
  }
  const lower = name.toLowerCase();
  if (output.mime?.startsWith('image/') || IMAGE_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
    return 'image';
  }
  return 'file';
}

/** Stored files by kind; the JSON details file is not one of them. */
export function fileCounts(outputs: readonly RunStepOutputResponse[]): FileCounts {
  const counts: FileCounts = { video: 0, audio: 0, image: 0, file: 0 };
  for (const output of outputs) {
    if (!isJsonOutput(output)) {
      counts[fileKind(output)] += 1;
    }
  }
  return counts;
}

/** The pretty text of a JSON list, below the parse bound; anything else as it is. */
function textView(text: string): string {
  const trimmed = text.trim();
  if (trimmed.startsWith('[') && trimmed.length < TABLE_PARSE_LIMIT) {
    const parsed = parseJson(trimmed);
    if (Array.isArray(parsed)) {
      return JSON.stringify(parsed, null, 2);
    }
  }
  return text;
}

/**
 * How a completed step's result is drawn: the first rule that applies. `text` is what the step's
 * result reads as, which is `step.output` unless an edit replaced it.
 */
export function stepOutcome(step: RunStepResponse, text: string | undefined): Outcome {
  if (step.type === 'generate_storyboard') {
    const storyboard = parseStoryboardOutput(text);
    if (storyboard) {
      return { kind: 'storyboard', storyboard };
    }
  }
  if (step.type === 'iterate' && (step.children ?? []).length > 0) {
    return { kind: 'iterate' };
  }
  if (text !== undefined && text.trim() !== '') {
    const rows = parseTableRows(text);
    if (rows) {
      return { kind: 'table', rows, columns: tableColumns(rows) };
    }
    if (parseReport(text)) {
      return { kind: 'report' };
    }
    return { kind: 'text', text: textView(text) };
  }
  const outputs = step.outputs ?? [];
  const counts = fileCounts(outputs);
  if (counts.video + counts.audio + counts.image + counts.file > 0) {
    return { kind: 'files', counts };
  }
  return outputs.length > 0 ? { kind: 'details' } : { kind: 'empty' };
}

export interface IterateItem {
  /** 1-based, from the item's index. */
  n: number;
  state: StepState;
  /** The output text cut to `ITEM_TEXT_LIMIT` code points with an ellipsis; null without text. */
  text: string | null;
  full: string | null;
  files: number;
}

const ITEM_STATES: Record<string, StepState> = {
  running: 'running',
  completed: 'completed',
  error: 'error',
  skipped: 'skipped',
};

function cut(text: string): string {
  const points = Array.from(text);
  return points.length > ITEM_TEXT_LIMIT ? `${points.slice(0, ITEM_TEXT_LIMIT).join('')}…` : text;
}

export function iterateSummary(step: RunStepResponse): {
  succeeded: number;
  total: number;
  items: IterateItem[];
} {
  const children = [...(step.children ?? [])].sort((a, b) => a.itemIndex - b.itemIndex);
  const items = children.map((child): IterateItem => {
    const hasText = child.output !== undefined && child.output.trim() !== '';
    return {
      n: child.itemIndex + 1,
      state: ITEM_STATES[child.status] ?? 'pending',
      text: hasText ? cut(child.output!) : null,
      full: hasText ? child.output! : null,
      files: child.outputs.length,
    };
  });
  return {
    succeeded: items.filter((item) => item.state === 'completed').length,
    total: items.length,
    items,
  };
}

export interface AdviceFacts {
  pickedUpAt: string | undefined;
  runStartedAt: string | undefined;
  runStatus: string | undefined;
  stepState: StepState;
}

export interface Advice {
  saved: boolean;
  longRun: 0 | 1 | 2;
  /** Time to the next note, or null when none is left to come. */
  nextChangeInMs: number | null;
}

function msSince(iso: string | undefined, nowMs: number): number | null {
  if (!iso) {
    return null;
  }
  const at = Date.parse(iso);
  return Number.isNaN(at) ? null : Math.max(0, nowMs - at);
}

/** The notes a picked-up running step shows, from server times and the clock. */
export function runningAdvice(facts: AdviceFacts, nowMs: number): Advice {
  if (facts.stepState !== 'running' || facts.runStatus !== 'running') {
    return { saved: false, longRun: 0, nextChangeInMs: null };
  }
  const remaining: number[] = [];
  const sincePickup = msSince(facts.pickedUpAt, nowMs);
  const saved = sincePickup !== null && sincePickup >= SAVED_NOTE_MS;
  if (sincePickup !== null && !saved) {
    remaining.push(SAVED_NOTE_MS - sincePickup);
  }
  const sinceStart = msSince(facts.runStartedAt, nowMs);
  let longRun: 0 | 1 | 2 = 0;
  if (sinceStart !== null) {
    if (sinceStart >= VERY_LONG_RUN_MS) {
      longRun = 2;
    } else {
      longRun = sinceStart >= LONG_RUN_MS ? 1 : 0;
      remaining.push((longRun === 1 ? VERY_LONG_RUN_MS : LONG_RUN_MS) - sinceStart);
    }
  }
  return { saved, longRun, nextChangeInMs: remaining.length > 0 ? Math.min(...remaining) : null };
}

export interface RunMeta {
  position: { current: number; total: number; stepId: string } | null;
  finishedAt: string | null;
  durationSeconds: number | null;
  credits: number | null;
}

/** The facts under the run heading: where a live run is, how a finished one went and what it cost. */
export function runMeta(run: RunResponse): RunMeta {
  const steps = run.steps ?? [];
  const progress = runProgress(steps, run.status);
  const atPosition = progress ? orderedSteps(steps)[progress.current - 1] : undefined;
  const started = run.startedAt ? Date.parse(run.startedAt) : Number.NaN;
  const finished = run.finishedAt ? Date.parse(run.finishedAt) : Number.NaN;
  return {
    position:
      progress && atPosition
        ? { current: progress.current, total: progress.total, stepId: stepKey(atPosition) }
        : null,
    finishedAt: run.finishedAt && !Number.isNaN(finished) ? run.finishedAt : null,
    durationSeconds:
      !Number.isNaN(started) && !Number.isNaN(finished) && finished >= started
        ? Math.floor((finished - started) / 1000)
        : null,
    credits: run.chargedCredits !== undefined && run.chargedCredits > 0 ? run.chargedCredits : null,
  };
}

/** The 1-based position of a step among the run's steps ordered by `stepOrder`. */
export function stepPosition(steps: readonly RunStepResponse[], step: RunStepResponse): number {
  const index = orderedSteps(steps).findIndex((other) => stepKey(other) === stepKey(step));
  return index >= 0 ? index + 1 : (step.stepOrder ?? 1);
}

export interface InputRow {
  key: string;
  value: string;
}

export type InputRows =
  | { kind: 'file'; name: string }
  | { kind: 'json'; rows: InputRow[] }
  | { kind: 'text'; text: string }
  | { kind: 'none' }
  | { kind: 'empty' };

function jsonValue(value: unknown): string {
  return typeof value === 'string' ? value : (JSON.stringify(value) ?? String(value));
}

/**
 * What the run input panel shows of the input itself. `uploadCount` is the run's uploaded files
 * once known, else null: "without input" is claimed only when the uploads are known to be none.
 */
export function inputRows(run: RunResponse, uploadCount: number | null): InputRows {
  const input = run.input;
  const nothing: InputRows = uploadCount === 0 ? { kind: 'empty' } : { kind: 'none' };
  if (orderedSteps(run.steps)[0]?.mode === 'remote') {
    return input && input.trim() !== '' && !looksLikeJson(input)
      ? { kind: 'file', name: input }
      : nothing;
  }
  if (!input || input.trim() === '') {
    return nothing;
  }
  const parsed = parseJson(input);
  if (isRecord(parsed)) {
    return {
      kind: 'json',
      rows: Object.entries(parsed).map(([key, value]) => ({ key, value: jsonValue(value) })),
    };
  }
  return { kind: 'text', text: input };
}
