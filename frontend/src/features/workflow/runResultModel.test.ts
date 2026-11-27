import type { RunResponse, RunStepChildResponse, RunStepResponse } from '@flow-cortex/api-client';
import {
  codePointLength,
  fileCounts,
  inputRows,
  isTableShaped,
  iterateSummary,
  runMeta,
  runningAdvice,
  sortRows,
  stepOutcome,
  stepPosition,
  tableColumns,
  visibleRows,
  ITEM_TEXT_LIMIT,
  LONG_RUN_MS,
  SAVED_NOTE_MS,
  TABLE_PARSE_LIMIT,
  TABLE_ROW_LIMIT,
  VERY_LONG_RUN_MS,
  type TableRow,
} from './runResultModel';

function step(overrides: Partial<RunStepResponse> = {}): RunStepResponse {
  return {
    id: 's1',
    stepOrder: 1,
    type: 'echo',
    mode: 'inline',
    status: 'completed',
    attempt: 1,
    deliveryCount: 0,
    outputs: [],
    children: [],
    ...overrides,
  };
}

function child(overrides: Partial<RunStepChildResponse> = {}): RunStepChildResponse {
  return { id: 'c0', itemIndex: 0, status: 'completed', attempt: 1, outputs: [], ...overrides };
}

function run(overrides: Partial<RunResponse> = {}): RunResponse {
  return { id: 'r1', status: 'completed', steps: [step()], ...overrides };
}

const outcome = (text: string | undefined, overrides: Partial<RunStepResponse> = {}) =>
  stepOutcome(step({ ...(text === undefined ? {} : { output: text }), ...overrides }), text);

describe('stepOutcome', () => {
  it('draws a readable storyboard of a generate_storyboard step', () => {
    const text = '{"scene1Prompt":"a","scene2Prompt":"b"}';
    expect(outcome(text, { type: 'generate_storyboard' }).kind).toBe('storyboard');
  });

  it('falls through to the table rule when a storyboard step holds no readable storyboard', () => {
    const text = '[{"scene1Prompt":"a","scene2Prompt":"b"}]';
    expect(outcome(text, { type: 'generate_storyboard' }).kind).toBe('table');
  });

  it('draws an iterate step with a child as the iterate view', () => {
    expect(outcome('[{"a":1}]', { type: 'iterate', children: [child()] }).kind).toBe('iterate');
  });

  it('falls through for an iterate step without children', () => {
    expect(outcome('hello', { type: 'iterate' }).kind).toBe('text');
  });

  it('draws a table-shaped output as a table', () => {
    const result = outcome('[{"name":"A","views":10}]');
    expect(result.kind).toBe('table');
  });

  it('draws a JSON object as a report', () => {
    expect(outcome('{"durationMs":1000}').kind).toBe('report');
  });

  it('shows a JSON list that is not a table pretty-printed over 16 characters', () => {
    const result = outcome('["a","b"]');
    expect(result).toEqual({ kind: 'text', text: '[\n  "a",\n  "b"\n]' });
    expect(codePointLength(result.kind === 'text' ? result.text : '')).toBe(16);
  });

  it('shows a very long JSON list as it is', () => {
    const long = `[${'"a",'.repeat(TABLE_PARSE_LIMIT / 4)}"b"]`;
    expect(outcome(long)).toEqual({ kind: 'text', text: long });
  });

  it('draws plain text as text', () => {
    expect(outcome('hello')).toEqual({ kind: 'text', text: 'hello' });
  });

  it('draws stored non-JSON files with no text as files', () => {
    const outputs = [{ name: 'a.mp4', mime: 'video/mp4', bytes: 1 }];
    expect(outcome(undefined, { outputs }).kind).toBe('files');
  });

  it('draws only a JSON details file as nothing of its own', () => {
    const outputs = [{ name: 'd.json', mime: 'application/json', bytes: 1 }];
    expect(outcome(undefined, { outputs }).kind).toBe('details');
  });

  it('draws nothing as empty, also for blank text', () => {
    expect(outcome(undefined).kind).toBe('empty');
    expect(outcome('  \n').kind).toBe('empty');
  });
});

describe('isTableShaped and tableColumns', () => {
  const rowsOf = (text: string): TableRow[] => {
    const parsed: unknown = JSON.parse(text);
    return parsed as TableRow[];
  };

  it('accepts rows with a shared key and lists every key in first-seen order', () => {
    const text = '[{"name":"A","views":10},{"name":"B","likes":3}]';
    expect(isTableShaped(text)).toBe(true);
    expect(tableColumns(rowsOf(text))).toEqual(['name', 'views', 'likes']);
  });

  it.each([
    ['an empty list', '[]'],
    ['a nested value', '[{"a":{"b":1}}]'],
    ['rows sharing no key', '[{"a":1},{"b":2}]'],
    ['an object without keys', '[{}]'],
    ['a list of numbers', '[1,2]'],
    ['an object', '{"a":1}'],
    ['text', 'hello'],
    ['too long a text', `[{"a":"${'x'.repeat(TABLE_PARSE_LIMIT)}"}]`],
  ])('rejects %s', (_label, text) => {
    expect(isTableShaped(text)).toBe(false);
  });

  it('tolerates surrounding whitespace', () => {
    expect(isTableShaped('  \n[{"a":1}]\n')).toBe(true);
  });
});

describe('sortRows', () => {
  const rows: TableRow[] = [{ views: 10 }, { views: null }, { views: 2 }];
  const collator = new Intl.Collator('en', { numeric: true });
  const views = (direction: 'asc' | 'desc' | null) =>
    sortRows(rows, 'views', direction, collator).map(({ row }) => row.views);

  it('sorts numbers numerically with empty cells last', () => {
    expect(views('asc')).toEqual([2, 10, null]);
  });

  it('keeps empty cells last when descending', () => {
    expect(views('desc')).toEqual([10, 2, null]);
  });

  it('keeps the original order unsorted and the original index on each row', () => {
    expect(views(null)).toEqual([10, null, 2]);
    expect(sortRows(rows, 'views', 'asc', collator).map(({ index }) => index)).toEqual([2, 0, 1]);
  });

  it('puts a missing cell with the empty ones', () => {
    const sparse: TableRow[] = [{ a: 1 }, {}, { a: 0 }];
    expect(sortRows(sparse, 'a', 'asc', collator).map(({ row }) => row.a)).toEqual([
      0,
      1,
      undefined,
    ]);
  });

  it('sorts Vietnamese text by its collation', () => {
    const names: TableRow[] = [{ n: 'Ánh' }, { n: 'An' }, { n: 'Bảo' }];
    const vi = new Intl.Collator('vi', { numeric: true });
    expect(sortRows(names, 'n', 'asc', vi).map(({ row }) => row.n)).toEqual(['An', 'Ánh', 'Bảo']);
  });

  it('does not change the rows it was given', () => {
    const before = JSON.stringify(rows);
    sortRows(rows, 'views', 'asc', collator);
    expect(JSON.stringify(rows)).toBe(before);
  });
});

describe('visibleRows', () => {
  it('cuts 1,200 rows to 500 and states the total', () => {
    const many = Array.from({ length: 1200 }, (_, index) => ({ index, row: { a: index } }));
    const shown = visibleRows(many);
    expect(shown.rows).toHaveLength(TABLE_ROW_LIMIT);
    expect(shown.total).toBe(1200);
    expect(shown.rows[0]?.index).toBe(0);
  });

  it('shows every row of a small table', () => {
    expect(visibleRows([{ index: 0, row: {} }])).toEqual({
      rows: [{ index: 0, row: {} }],
      total: 1,
    });
  });
});

describe('codePointLength', () => {
  it('counts code points, not UTF-16 units', () => {
    expect(codePointLength('Xin chào 👋')).toBe(10);
    expect(codePointLength('')).toBe(0);
  });
});

describe('fileCounts', () => {
  it('counts by kind from the mime type, then the extension, and ignores JSON details', () => {
    expect(
      fileCounts([
        { name: 'a.mp4', mime: 'video/mp4', bytes: 1 },
        { name: 'b.jpg', mime: 'image/jpeg', bytes: 1 },
        { name: 'c.png', mime: '', bytes: 1 },
        { name: 'd.zip', mime: 'application/zip', bytes: 1 },
        { name: 'd.json', mime: 'application/json', bytes: 1 },
      ]),
    ).toEqual({ video: 1, audio: 0, image: 2, file: 1 });
  });

  it('counts audio by mime or extension', () => {
    expect(
      fileCounts([
        { name: 'a.wav', mime: 'audio/wav', bytes: 1 },
        { name: 'b.mp3', mime: '', bytes: 1 },
      ]).audio,
    ).toBe(2);
  });
});

describe('iterateSummary', () => {
  it('summarises three mixed items in item order', () => {
    const summary = iterateSummary(
      step({
        type: 'iterate',
        children: [
          child({
            id: 'c2',
            itemIndex: 2,
            outputs: [{ name: 'v.mp4', mime: 'video/mp4', bytes: 1 }],
          }),
          child({ id: 'c0', itemIndex: 0, output: 'Hello' }),
          child({ id: 'c1', itemIndex: 1, status: 'error' }),
        ],
      }),
    );
    expect(summary.succeeded).toBe(2);
    expect(summary.total).toBe(3);
    expect(summary.items).toEqual([
      { n: 1, state: 'completed', text: 'Hello', full: 'Hello', files: 0 },
      { n: 2, state: 'error', text: null, full: null, files: 0 },
      { n: 3, state: 'completed', text: null, full: null, files: 1 },
    ]);
  });

  it('cuts a 250-character text to 200 code points and an ellipsis', () => {
    const full = 'x'.repeat(250);
    const [item] = iterateSummary(step({ children: [child({ output: full })] })).items;
    expect(item?.text).toBe(`${'x'.repeat(ITEM_TEXT_LIMIT)}…`);
    expect(item?.full).toBe(full);
  });

  it('maps item statuses onto the node state words', () => {
    const states = iterateSummary(
      step({
        children: ['running', 'completed', 'error', 'skipped', 'queued'].map((status, index) =>
          child({ id: `c${index}`, itemIndex: index, status }),
        ),
      }),
    ).items.map((item) => item.state);
    expect(states).toEqual(['running', 'completed', 'error', 'skipped', 'pending']);
  });
});

describe('runningAdvice', () => {
  const start = Date.parse('2026-10-01T10:00:00Z');
  const facts = (overrides: Partial<Parameters<typeof runningAdvice>[0]> = {}) => ({
    pickedUpAt: '2026-10-01T10:00:00Z',
    runStartedAt: '2026-10-01T10:00:00Z',
    runStatus: 'running',
    stepState: 'running' as const,
    ...overrides,
  });

  it('shows the saved note from 30,000 ms after pickup', () => {
    expect(runningAdvice(facts(), start + SAVED_NOTE_MS - 1).saved).toBe(false);
    expect(runningAdvice(facts(), start + SAVED_NOTE_MS).saved).toBe(true);
  });

  it('steps the long-run note at 5 and 10 minutes after the run started', () => {
    expect(runningAdvice(facts(), start + LONG_RUN_MS - 1000).longRun).toBe(0);
    expect(runningAdvice(facts(), start + LONG_RUN_MS).longRun).toBe(1);
    expect(runningAdvice(facts(), start + VERY_LONG_RUN_MS - 1).longRun).toBe(1);
    expect(runningAdvice(facts(), start + VERY_LONG_RUN_MS).longRun).toBe(2);
  });

  it('counts a time in the future as now', () => {
    const advice = runningAdvice(facts({ pickedUpAt: '2026-10-01T11:00:00Z' }), start);
    expect(advice.saved).toBe(false);
    expect(advice.nextChangeInMs).toBe(SAVED_NOTE_MS);
  });

  it('shows no note for an unreadable or missing time', () => {
    const advice = runningAdvice(
      facts({ pickedUpAt: 'soon', runStartedAt: undefined }),
      start + 1e9,
    );
    expect(advice).toEqual({ saved: false, longRun: 0, nextChangeInMs: null });
  });

  it('shows no note while the run waits for the member or the step is not running', () => {
    expect(runningAdvice(facts({ runStatus: 'waiting_approval' }), start + 1e9)).toEqual({
      saved: false,
      longRun: 0,
      nextChangeInMs: null,
    });
    expect(runningAdvice(facts({ stepState: 'completed' }), start + 1e9).saved).toBe(false);
  });

  it('names the time to the next threshold', () => {
    expect(runningAdvice(facts(), start + 10_000).nextChangeInMs).toBe(20_000);
    expect(runningAdvice(facts(), start + 40_000).nextChangeInMs).toBe(LONG_RUN_MS - 40_000);
    expect(runningAdvice(facts(), start + VERY_LONG_RUN_MS).nextChangeInMs).toBeNull();
  });
});

describe('runMeta', () => {
  it('states the position of a live run', () => {
    const live = run({
      status: 'running',
      steps: [
        step({ id: 'a', stepOrder: 1 }),
        step({ id: 'b', stepOrder: 2, status: 'running', pickedUpAt: '2026-10-01T10:00:00Z' }),
        step({ id: 'c', stepOrder: 3, status: 'pending' }),
        step({ id: 'd', stepOrder: 4, status: 'pending' }),
      ],
    });
    expect(runMeta(live)).toEqual({
      position: { current: 2, total: 4, stepId: 'b' },
      finishedAt: null,
      durationSeconds: null,
      credits: null,
    });
  });

  it('states finish time, duration and credits of a finished run, with no position', () => {
    expect(
      runMeta(
        run({
          startedAt: '2026-10-01T10:00:00Z',
          finishedAt: '2026-10-01T10:01:20Z',
          chargedCredits: 12,
        }),
      ),
    ).toEqual({
      position: null,
      finishedAt: '2026-10-01T10:01:20Z',
      durationSeconds: 80,
      credits: 12,
    });
  });

  it('shows no credits when they are switched off or zero', () => {
    expect(runMeta(run()).credits).toBeNull();
    expect(runMeta(run({ chargedCredits: 0 })).credits).toBeNull();
  });

  it('gives no duration for a reversed or unreadable pair', () => {
    expect(
      runMeta(run({ startedAt: '2026-10-01T10:02:00Z', finishedAt: '2026-10-01T10:01:00Z' }))
        .durationSeconds,
    ).toBeNull();
    expect(
      runMeta(run({ startedAt: 'x', finishedAt: '2026-10-01T10:01:00Z' })).durationSeconds,
    ).toBeNull();
  });
});

describe('stepPosition', () => {
  it('numbers steps by stepOrder even when the list is out of order', () => {
    const steps = [
      step({ id: 'c', stepOrder: 3 }),
      step({ id: 'a', stepOrder: 1 }),
      step({ id: 'b', stepOrder: 2 }),
    ];
    expect(steps.map((one) => stepPosition(steps, one))).toEqual([3, 1, 2]);
  });
});

describe('inputRows', () => {
  const textRun = (input: string | undefined, mode: 'inline' | 'remote' = 'inline') => {
    const base = run({ steps: [step({ mode })] });
    return input === undefined ? base : { ...base, input };
  };

  it('lists a JSON object as one row per key, with other values as compact JSON', () => {
    expect(
      inputRows(
        textRun(
          '{"sourceUrl":"https://www.tiktok.com/@a/video/1","count":3,"ok":true,"tags":["x"]}',
        ),
        0,
      ),
    ).toEqual({
      kind: 'json',
      rows: [
        { key: 'sourceUrl', value: 'https://www.tiktok.com/@a/video/1' },
        { key: 'count', value: '3' },
        { key: 'ok', value: 'true' },
        { key: 'tags', value: '["x"]' },
      ],
    });
  });

  it('gives non-blank text in full', () => {
    expect(inputRows(textRun('Summarise this article about rice prices'), 0)).toEqual({
      kind: 'text',
      text: 'Summarise this article about rice prices',
    });
  });

  it('names the file of a run whose first step reads an upload', () => {
    expect(inputRows(textRun('holiday clip.mp4', 'remote'), 1)).toEqual({
      kind: 'file',
      name: 'holiday clip.mp4',
    });
  });

  it('never shows JSON rows for a file run holding a JSON reference', () => {
    expect(inputRows(textRun('{"objectKey":"uploads/1"}', 'remote'), 1)).toEqual({ kind: 'none' });
  });

  it('claims the run started without input only once the uploads are known to be none', () => {
    expect(inputRows(textRun(undefined), 0)).toEqual({ kind: 'empty' });
    expect(inputRows(textRun('   '), 0)).toEqual({ kind: 'empty' });
    expect(inputRows(textRun(undefined), 2)).toEqual({ kind: 'none' });
    expect(inputRows(textRun(undefined), null)).toEqual({ kind: 'none' });
    expect(inputRows(textRun(undefined, 'remote'), 0)).toEqual({ kind: 'empty' });
  });
});
