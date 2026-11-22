import type { RunResponse, RunStepResponse } from '@flow-cortex/api-client';
import {
  clampSplit,
  elapsedSeconds,
  initialView,
  inputEdgeState,
  inputSummary,
  looksLikeJson,
  panIntoView,
  readSplit,
  runCanvasGraph,
  runProgress,
  shouldFollow,
  splitKey,
  FIT_PADDING,
  INPUT_NODE_ID,
  NODE_PITCH,
  NODE_WIDTH,
  READABLE_FIT,
} from './runCanvasModel';
import { stepKey } from './stepDisplay';

function step(overrides: Partial<RunStepResponse> = {}): RunStepResponse {
  return {
    id: 's1',
    stepOrder: 1,
    type: 'echo',
    mode: 'inline',
    status: 'pending',
    attempt: 1,
    deliveryCount: 0,
    outputs: [],
    ...overrides,
  };
}

const steps = (...statuses: string[]): RunStepResponse[] =>
  statuses.map((status, index) =>
    step({
      id: `s${index + 1}`,
      stepOrder: index + 1,
      status,
      ...(status === 'running' ? { pickedUpAt: '2026-10-01T10:00:00Z' } : {}),
    }),
  );

const run = (overrides: Partial<RunResponse> = {}): RunResponse => ({
  id: 'r1',
  status: 'running',
  steps: steps('completed', 'running', 'pending'),
  ...overrides,
});

describe('runCanvasGraph', () => {
  it('places Input at 0 and step k at k×340, sorted by stepOrder even when given out of order', () => {
    const shuffled = [
      step({ id: 'c', stepOrder: 3 }),
      step({ id: 'a', stepOrder: 1 }),
      step({ id: 'b', stepOrder: 2 }),
    ];
    const { nodes } = runCanvasGraph(run({ steps: shuffled }));
    expect(nodes.map((node) => [node.id, node.position.x, node.position.y])).toEqual([
      [INPUT_NODE_ID, 0, 0],
      ['a', 340, 0],
      ['b', 680, 0],
      ['c', 1020, 0],
    ]);
    expect(nodes.every((node) => node.width === NODE_WIDTH)).toBe(true);
    expect(NODE_PITCH).toBe(340);
  });

  it('joins Input→1 and k→k+1 with edgeState (completed/running/pending → done, active, pending; error then pending → broken)', () => {
    const three = runCanvasGraph(run({ steps: steps('completed', 'running', 'pending') }));
    expect(three.edges.map((edge) => [edge.source, edge.target, edge.state])).toEqual([
      [INPUT_NODE_ID, 's1', 'done'],
      ['s1', 's2', 'active'],
      ['s2', 's3', 'pending'],
    ]);
    const failed = runCanvasGraph(run({ steps: steps('completed', 'error', 'pending') }));
    expect(failed.edges.map((edge) => edge.state)).toEqual(['done', 'pending', 'broken']);
  });

  it('keys step nodes by stepKey, also for a step without an id', () => {
    const noId = step({ stepOrder: 4 });
    delete noId.id;
    const { nodes } = runCanvasGraph(run({ steps: [noId] }));
    expect(nodes[1]!.id).toBe(stepKey(noId));
  });

  it('returns no nodes for a run with no steps', () => {
    expect(runCanvasGraph(run({ steps: [] }))).toEqual({ nodes: [], edges: [] });
    expect(runCanvasGraph({ id: 'r1' })).toEqual({ nodes: [], edges: [] });
  });
});

describe('inputEdgeState', () => {
  it.each<[string, string]>([
    ['completed', 'done'],
    ['running', 'active'],
    ['pending', 'pending'],
    ['error', 'pending'],
    ['skipped', 'pending'],
  ])('maps a first step that is %s to %s', (status, expected) => {
    expect(inputEdgeState(step({ status }))).toBe(expected);
  });
});

describe('looksLikeJson', () => {
  it('is true for a JSON object or array and false for text, numbers and empty', () => {
    expect(looksLikeJson('{"uploadId":"u-1"}')).toBe(true);
    expect(looksLikeJson('[1]')).toBe(true);
    expect(looksLikeJson('holiday clip.mp4')).toBe(false);
    expect(looksLikeJson('42')).toBe(false);
    expect(looksLikeJson('')).toBe(false);
  });
});

describe('inputSummary', () => {
  it('names the uploaded file when the first step is remote', () => {
    const summary = inputSummary(
      run({ input: 'holiday clip.mp4', steps: [step({ mode: 'remote' })] }),
    );
    expect(summary).toEqual({ kind: 'file', text: 'holiday clip.mp4' });
  });

  it('cuts a 200-character text to 60 characters and "…" and keeps the full text as the title', () => {
    const input = 'a'.repeat(200);
    const summary = inputSummary(run({ input }));
    expect(summary).toEqual({ kind: 'text', text: `${'a'.repeat(60)}…`, title: input });
  });

  it('keeps a text of 60 characters whole', () => {
    const input = 'b'.repeat(60);
    expect(inputSummary(run({ input }))).toEqual({ kind: 'text', text: input, title: input });
  });

  it('does not split a character made of two code units', () => {
    const input = '😀'.repeat(61);
    const summary = inputSummary(run({ input }));
    expect(summary).toEqual({ kind: 'text', text: `${'😀'.repeat(60)}…`, title: input });
  });

  it('falls back to the run-input label for a JSON reference, an empty or a blank input', () => {
    expect(inputSummary(run({ input: '{"uploadId":"u-1"}' }))).toEqual({ kind: 'label' });
    expect(
      inputSummary(run({ input: '{"uploadId":"u-1"}', steps: [step({ mode: 'remote' })] })),
    ).toEqual({ kind: 'label' });
    expect(inputSummary(run({ input: '' }))).toEqual({ kind: 'label' });
    expect(inputSummary(run({ input: '   ' }))).toEqual({ kind: 'label' });
    expect(inputSummary(run({}))).toEqual({ kind: 'label' });
  });

  it('reads the mode of the first step by order, not by position', () => {
    const summary = inputSummary(
      run({
        input: 'clip.mp4',
        steps: [step({ id: 'b', stepOrder: 2 }), step({ id: 'a', stepOrder: 1, mode: 'remote' })],
      }),
    );
    expect(summary.kind).toBe('file');
  });
});

describe('runProgress', () => {
  it('is the running step of four', () => {
    expect(runProgress(steps('completed', 'running', 'pending', 'pending'), 'running')).toEqual({
      current: 2,
      total: 4,
    });
  });

  it('is the number of completed steps plus one between steps', () => {
    expect(runProgress(steps('completed', 'completed', 'pending', 'pending'), 'running')).toEqual({
      current: 3,
      total: 4,
    });
  });

  it('is the checkpoint a waiting run needs the member on', () => {
    const five = [
      ...steps('completed', 'completed'),
      step({ id: 'cp', stepOrder: 3, type: 'APPROVAL_CHECKPOINT' }),
      step({ id: 's4', stepOrder: 4 }),
      step({ id: 's5', stepOrder: 5 }),
    ];
    expect(runProgress(five, 'waiting_approval')).toEqual({ current: 3, total: 5 });
  });

  it('is shown for a cancelling run', () => {
    expect(runProgress(steps('running', 'pending'), 'cancelling')).toEqual({
      current: 1,
      total: 2,
    });
  });

  it.each(['completed', 'error', 'cancelled', undefined])('is null when the run is %s', (status) => {
    expect(runProgress(steps('completed', 'running'), status)).toBeNull();
  });

  it('is null for a live run with no steps', () => {
    expect(runProgress([], 'running')).toBeNull();
  });

  it('is never more than N', () => {
    expect(runProgress(steps('completed', 'completed'), 'running')).toEqual({
      current: 2,
      total: 2,
    });
  });

  it('counts X as the 1-based position even when stepOrder starts at 10', () => {
    const offset = [
      step({ id: 'a', stepOrder: 10, status: 'completed' }),
      step({ id: 'b', stepOrder: 11, status: 'running', pickedUpAt: '2026-10-01T10:00:00Z' }),
      step({ id: 'c', stepOrder: 12 }),
    ];
    expect(runProgress(offset, 'running')).toEqual({ current: 2, total: 3 });
  });

  it('takes a running step that was not picked up for the current one', () => {
    const waiting = [step({ id: 'a', stepOrder: 1, status: 'running' })];
    expect(runProgress(waiting, 'running')).toEqual({ current: 1, total: 1 });
  });
});

describe('elapsedSeconds', () => {
  const now = Date.parse('2026-10-01T10:01:00Z');

  it('is the whole seconds since the pickup', () => {
    expect(elapsedSeconds('2026-10-01T10:00:18Z', now)).toBe(42);
    expect(elapsedSeconds('2026-10-01T10:00:18Z', now + 1500)).toBe(43);
  });

  it('is null without a pickup time or with one that is not a date', () => {
    expect(elapsedSeconds(undefined, now)).toBeNull();
    expect(elapsedSeconds('soon', now)).toBeNull();
  });

  it('reads zero when the pickup is later than the device clock', () => {
    expect(elapsedSeconds('2026-10-01T10:01:03Z', now)).toBe(0);
  });
});

describe('initialView', () => {
  const box = (x: number, width: number, height = 120) => ({ x, y: 0, width, height });
  const graphWidth = (steps: number) => steps * NODE_PITCH + NODE_WIDTH;

  it('fits a one-step run on a 700px track', () => {
    const view = initialView(box(0, graphWidth(1)), { width: 700, height: 400 }, box(340, 240));
    expect(view.zoom).toBeCloseTo(0.97, 2);
    const left = view.x;
    const right = graphWidth(1) * view.zoom + view.x;
    expect(left).toBeGreaterThanOrEqual(0);
    expect(right).toBeLessThanOrEqual(700);
    expect(left + right).toBeCloseTo(700, 5);
  });

  it('opens a three-step run on a 700px track at 100% centred on the default step', () => {
    const fit = 700 / (graphWidth(3) * (1 + 2 * FIT_PADDING));
    expect(fit).toBeCloseTo(0.45, 2);
    const focus = box(2 * NODE_PITCH, NODE_WIDTH);
    const view = initialView(box(0, graphWidth(3)), { width: 700, height: 400 }, focus);
    expect(view.zoom).toBe(1);
    expect(focus.x + focus.width / 2 + view.x).toBe(350);
    expect(focus.y + focus.height / 2 + view.y).toBe(200);
  });

  it('opens a fifteen-step run on a 400px track at 100% centred on node 9', () => {
    const focus = box(9 * NODE_PITCH, NODE_WIDTH);
    const view = initialView(box(0, graphWidth(15)), { width: 400, height: 400 }, focus);
    expect(view.zoom).toBe(1);
    expect(focus.x + focus.width / 2 + view.x).toBe(200);
  });

  it('fits at exactly 0.67 and opens at 100% below it', () => {
    const graph = box(0, graphWidth(2));
    const canvasFor = (fit: number) => ({
      width: fit * graph.width * (1 + 2 * FIT_PADDING),
      height: 2000,
    });
    const focus = box(NODE_PITCH, NODE_WIDTH);
    expect(READABLE_FIT).toBe(0.67);
    expect(initialView(graph, canvasFor(0.67), focus).zoom).toBeCloseTo(0.67, 6);
    expect(initialView(graph, canvasFor(0.66), focus).zoom).toBe(1);
  });

  it('is limited by the canvas height when the graph is tall', () => {
    const view = initialView(
      { x: 0, y: 0, width: 300, height: 1000 },
      { width: 3000, height: 1000 },
      box(0, NODE_WIDTH),
    );
    expect(view.zoom).toBeCloseTo(1000 / (1000 * (1 + 2 * FIT_PADDING)), 6);
  });

  it('stays finite on a canvas with no size yet', () => {
    const view = initialView(box(0, graphWidth(1)), { width: 0, height: 0 }, box(340, 240));
    expect(Number.isFinite(view.x) && Number.isFinite(view.y) && Number.isFinite(view.zoom)).toBe(
      true,
    );
    expect(view.zoom).toBe(1);
  });
});

describe('shouldFollow', () => {
  it('always follows a step that needs the member', () => {
    expect(shouldFollow('needs-you', true)).toBe(true);
    expect(shouldFollow('needs-you', false)).toBe(true);
  });

  it('follows a passive viewer and leaves a member who moved the view', () => {
    expect(shouldFollow('running', false)).toBe(true);
    expect(shouldFollow('running', true)).toBe(false);
    expect(shouldFollow('error', true)).toBe(false);
  });
});

describe('panIntoView', () => {
  const canvas = { width: 600, height: 400 };
  const node = { x: 340, y: 0, width: 240, height: 120 };

  it('leaves a node that is inside the view alone', () => {
    const viewport = { x: 0, y: 100, zoom: 1 };
    expect(panIntoView(node, viewport, canvas)).toBe(viewport);
  });

  it('shifts the view so a node to the right has its right edge inside, keeping the zoom', () => {
    const viewport = { x: 0, y: 100, zoom: 0.8 };
    const far = { ...node, x: 1020 };
    const next = panIntoView(far, viewport, canvas);
    expect(next.zoom).toBe(0.8);
    expect(next.y).toBe(100);
    expect((far.x + far.width) * 0.8 + next.x).toBeLessThanOrEqual(600);
    expect(far.x * 0.8 + next.x).toBeGreaterThanOrEqual(0);
  });

  it('shifts the view so a node to the left has its left edge inside', () => {
    const viewport = { x: -900, y: 100, zoom: 1 };
    const next = panIntoView(node, viewport, canvas);
    expect(node.x + next.x).toBeGreaterThanOrEqual(0);
    expect(node.x + node.width + next.x).toBeLessThanOrEqual(600);
  });

  it('shifts the view vertically when the node is below or above', () => {
    const below = panIntoView(node, { x: 0, y: 380, zoom: 1 }, canvas);
    expect(node.y + below.y).toBeGreaterThanOrEqual(0);
    const above = panIntoView(node, { x: 0, y: -300, zoom: 1 }, canvas);
    expect(node.y + node.height + above.y).toBeLessThanOrEqual(400);
  });

  it('aligns the left edge when the node is wider than the canvas', () => {
    const next = panIntoView(node, { x: 0, y: 100, zoom: 1 }, { width: 200, height: 400 });
    expect(node.x + next.x).toBeGreaterThanOrEqual(0);
    expect(node.x + next.x).toBeLessThan(200);
  });
});

describe('clampSplit and readSplit', () => {
  it('clamps to 30–75', () => {
    expect(clampSplit(10)).toBe(30);
    expect(clampSplit(90)).toBe(75);
    expect(clampSplit(44)).toBe(44);
    expect(clampSplit(Number.NaN)).toBe(40);
  });

  it('reads a stored value, clamped, and 40 when it is not a number', () => {
    expect(readSplit('90')).toBe(75);
    expect(readSplit('wide')).toBe(40);
    expect(readSplit('')).toBe(40);
    expect(readSplit(null)).toBe(40);
    expect(readSplit('44')).toBe(44);
  });
});

describe('splitKey', () => {
  it('moves by 2 on ArrowRight three times from 40 to 46', () => {
    let value = 40;
    for (let i = 0; i < 3; i += 1) {
      value = splitKey(value, 'ArrowRight');
    }
    expect(value).toBe(46);
    expect(splitKey(46, 'ArrowLeft')).toBe(44);
  });

  it('jumps to the ends on End and Home and back to 40 on Enter', () => {
    expect(splitKey(46, 'End')).toBe(75);
    expect(splitKey(46, 'Home')).toBe(30);
    expect(splitKey(75, 'Enter')).toBe(40);
  });

  it('stays inside the bounds', () => {
    expect(splitKey(75, 'ArrowRight')).toBe(75);
    expect(splitKey(30, 'ArrowLeft')).toBe(30);
  });

  it('leaves the value alone for any other key', () => {
    expect(splitKey(46, 'Tab')).toBe(46);
  });
});
