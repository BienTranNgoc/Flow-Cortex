import type { RunStepResponse } from '@flow-cortex/api-client';
import i18n from '../../lib/i18n';
import {
  choiceTakenBack,
  defaultStep,
  edgeState,
  selectedStep,
  stepDuration,
  stepKey,
  stepLabel,
  stepState,
  formatTime,
  type StepChoice,
} from './stepDisplay';
import { outputFields } from './stepOutputFields';

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

const checkpoint = (overrides: Partial<RunStepResponse> = {}) =>
  step({ id: 'cp', stepOrder: 2, type: 'APPROVAL_CHECKPOINT', ...overrides });

function withoutId(source: RunStepResponse): RunStepResponse {
  const copy = { ...source };
  delete copy.id;
  return copy;
}

describe('stepLabel', () => {
  it.each<[string, number, string]>([
    ['probe', 1, 'Inspect the file'],
    ['extract_audio', 2, 'Extract source audio'],
    ['APPROVAL_CHECKPOINT', 2, 'Review'],
    ['echo', 3, 'Step 3'],
  ])('names type %s at order %i "%s"', (type, stepOrder, label) => {
    expect(stepLabel(step({ type, stepOrder }), i18n.getFixedT('en'))).toBe(label);
  });
});

describe('stepKey', () => {
  it('is the id, else a value unique to the step order', () => {
    expect(stepKey(step({ id: 'abc', stepOrder: 2 }))).toBe('abc');
    const first = stepKey({ stepOrder: 1 });
    const second = stepKey({ stepOrder: 2 });
    expect(first).not.toBe(second);
    expect(first).not.toBe('');
  });

  it('keeps steps without an id apart in state and selection', () => {
    const steps = [
      withoutId(step({ stepOrder: 1, status: 'completed' })),
      withoutId(step({ stepOrder: 2, type: 'APPROVAL_CHECKPOINT' })),
    ];
    expect(stepState(steps[0]!, steps, 'waiting_approval')).toBe('completed');
    expect(stepState(steps[1]!, steps, 'waiting_approval')).toBe('needs-you');
  });
});

describe('stepState', () => {
  const running = [step({ status: 'running' })];
  it.each<[string, RunStepResponse, string, string]>([
    ['pending', step(), 'running', 'pending'],
    ['running without pickup', step({ status: 'running' }), 'running', 'waiting'],
    [
      'running with pickup',
      step({ status: 'running', pickedUpAt: '2026-10-01T10:00:01Z' }),
      'running',
      'running',
    ],
    ['completed', step({ status: 'completed' }), 'completed', 'completed'],
    ['error', step({ status: 'error' }), 'error', 'error'],
    ['pending checkpoint of a run that is not waiting', checkpoint(), 'running', 'pending'],
  ])('%s reads %s -> %s', (_name, subject, runStatus, expected) => {
    expect(stepState(subject, running, runStatus)).toBe(expected);
  });

  it('marks the pending checkpoint of a waiting run as needs-you', () => {
    const steps = [step({ status: 'completed' }), checkpoint()];
    expect(stepState(steps[1]!, steps, 'waiting_approval')).toBe('needs-you');
    expect(stepState(steps[0]!, steps, 'waiting_approval')).toBe('completed');
  });

  it('marks only the first pending checkpoint of a waiting run', () => {
    const steps = [
      checkpoint({ id: 'cp1', stepOrder: 1 }),
      checkpoint({ id: 'cp2', stepOrder: 2 }),
    ];
    expect(stepState(steps[0]!, steps, 'waiting_approval')).toBe('needs-you');
    expect(stepState(steps[1]!, steps, 'waiting_approval')).toBe('pending');
  });
});

describe('edgeState', () => {
  it.each<[string, string, string]>([
    ['completed', 'completed', 'done'],
    ['completed', 'running', 'active'],
    ['completed', 'error', 'pending'],
    ['error', 'pending', 'broken'],
    ['completed', 'pending', 'pending'],
    ['pending', 'pending', 'pending'],
  ])('%s then %s is %s', (from, to, expected) => {
    const before = step({ id: 'a', status: from });
    const after = step({ id: 'b', stepOrder: 2, status: to });
    expect(edgeState(before, after)).toBe(expected);
  });
});

describe('defaultStep', () => {
  const done = (id: string, order: number) => step({ id, stepOrder: order, status: 'completed' });

  it('prefers the step that needs the member', () => {
    const steps = [
      step({ id: 'bad', status: 'error' }),
      checkpoint(),
      step({ id: 'run', stepOrder: 3, status: 'running' }),
    ];
    expect(defaultStep(steps, 'waiting_approval')?.id).toBe('cp');
  });

  it('then the first failed step', () => {
    const steps = [
      done('a', 1),
      step({ id: 'bad1', stepOrder: 2, status: 'error' }),
      step({ id: 'bad2', stepOrder: 3, status: 'error' }),
    ];
    expect(defaultStep(steps, 'error')?.id).toBe('bad1');
  });

  it('then the running step', () => {
    const steps = [
      done('a', 1),
      step({ id: 'run', stepOrder: 2, status: 'running' }),
      step({ id: 'p', stepOrder: 3 }),
    ];
    expect(defaultStep(steps, 'running')?.id).toBe('run');
  });

  it('then the last completed step', () => {
    const steps = [done('a', 1), done('b', 2), step({ id: 'p', stepOrder: 3 })];
    expect(defaultStep(steps, 'error')?.id).toBe('b');
  });

  it('then the first step', () => {
    const steps = [step({ id: 'p1' }), step({ id: 'p2', stepOrder: 2 })];
    expect(defaultStep(steps, 'running')?.id).toBe('p1');
  });

  it('is undefined with no steps', () => {
    expect(defaultStep([], 'running')).toBeUndefined();
  });
});

describe('selectedStep', () => {
  const steps = [
    step({ id: 'a', stepOrder: 1, status: 'completed' }),
    step({ id: 'b', stepOrder: 2, status: 'running' }),
  ];
  const pick = (stepId: string, runStatus: string, needsYouId?: string): StepChoice => ({
    stepId,
    runStatus,
    needsYouId,
  });

  it('keeps the choice across the same run status', () => {
    expect(selectedStep(steps, 'running', pick('a', 'running'))?.id).toBe('a');
  });

  it('drops the choice when the run becomes waiting_approval', () => {
    const waiting = [steps[0]!, checkpoint({ id: 'cp', stepOrder: 2 })];
    expect(selectedStep(waiting, 'waiting_approval', pick('a', 'running'))?.id).toBe('cp');
  });

  it('drops the choice when the run becomes error', () => {
    const failed = [steps[0]!, step({ id: 'b', stepOrder: 2, status: 'error' })];
    expect(selectedStep(failed, 'error', pick('a', 'running'))?.id).toBe('b');
  });

  it('keeps the choice when the run becomes completed', () => {
    const finished = [steps[0]!, step({ id: 'b', stepOrder: 2, status: 'completed' })];
    expect(selectedStep(finished, 'completed', pick('a', 'running'))?.id).toBe('a');
  });

  it('keeps a choice made while the run was already waiting', () => {
    const waiting = [steps[0]!, checkpoint({ id: 'cp', stepOrder: 2 })];
    expect(selectedStep(waiting, 'waiting_approval', pick('a', 'waiting_approval', 'cp'))?.id).toBe(
      'a',
    );
  });

  it('does not bring a taken-back choice back when the run returns to running', () => {
    const waiting = [steps[0]!, checkpoint({ id: 'cp', stepOrder: 2 })];
    const choice = pick('a', 'running');
    expect(choiceTakenBack(waiting, 'waiting_approval', choice)).toBe(true);
    expect(choiceTakenBack(steps, 'running', choice)).toBe(false);
  });

  it('takes back a choice made at one checkpoint when the run reaches the next', () => {
    const atSecond = [
      steps[0]!,
      checkpoint({ id: 'cp1', stepOrder: 2, status: 'completed' }),
      checkpoint({ id: 'cp2', stepOrder: 3 }),
    ];
    const choice = pick('a', 'waiting_approval', 'cp1');
    expect(selectedStep(atSecond, 'waiting_approval', choice)?.id).toBe('cp2');
    expect(choiceTakenBack(atSecond, 'waiting_approval', choice)).toBe(true);
  });

  it('keeps a choice made at a checkpoint while the run is still at it', () => {
    const waiting = [steps[0]!, checkpoint({ id: 'cp', stepOrder: 2 })];
    expect(choiceTakenBack(waiting, 'waiting_approval', pick('a', 'waiting_approval', 'cp'))).toBe(
      false,
    );
  });

  it('keeps a choice made under error while the run stays in error', () => {
    const failed = [steps[0]!, step({ id: 'b', stepOrder: 2, status: 'error' })];
    expect(choiceTakenBack(failed, 'error', pick('a', 'error'))).toBe(false);
  });

  it('falls back when the chosen step no longer exists', () => {
    expect(selectedStep(steps, 'running', pick('gone', 'running'))?.id).toBe('b');
  });

  it('takes back a choice whose step is no longer in the run', () => {
    expect(choiceTakenBack(steps, 'running', pick('gone', 'running'))).toBe(true);
    expect(choiceTakenBack(steps, 'completed', pick('gone', 'completed'))).toBe(true);
  });

  it('uses the default with no choice', () => {
    expect(selectedStep(steps, 'running', undefined)?.id).toBe('b');
  });
});

describe('stepDuration', () => {
  const en = i18n.getFixedT('en');

  it('calculates duration from startedAt and finishedAt', () => {
    const s = step({
      startedAt: '2026-10-17T10:00:00.000Z',
      finishedAt: '2026-10-17T10:00:11.300Z',
    });
    expect(stepDuration(s, en, 'en')).toBe('11.3s');
  });

  it('formats minutes and seconds when duration exceeds 60s', () => {
    const s = step({
      startedAt: '2026-10-17T10:00:00.000Z',
      finishedAt: '2026-10-17T10:01:25.000Z',
    });
    expect(stepDuration(s, en, 'en')).toBe('1m 25s');
  });

  it('falls back to durationMs in output report', () => {
    const s = step({ output: '{"durationMs": 4200}' });
    expect(stepDuration(s, en, 'en')).toBe('4.2s');
  });

  it('falls back to durationSeconds in output report', () => {
    const s = step({ output: '{"durationSeconds": 10}' });
    expect(stepDuration(s, en, 'en')).toBe('10s');
  });

  it('falls back to duration string in output report', () => {
    const s = step({ output: '{"duration": "11.3 s"}' });
    expect(stepDuration(s, en, 'en')).toBe('11.3 s');
  });

  it('returns null when no timing or duration is available', () => {
    expect(stepDuration(step(), en, 'en')).toBeNull();
  });

  it('writes durations and sizes with Vietnamese units and a comma decimal in vi', () => {
    const vi = i18n.getFixedT('vi');
    const timed = (seconds: string) =>
      step({ startedAt: '2026-10-17T10:00:00.000Z', finishedAt: `2026-10-17T10:${seconds}Z` });

    expect(stepDuration(timed('00:11.300'), vi, 'vi')).toBe('11,3 giây');
    expect(stepDuration(timed('01:25.000'), vi, 'vi')).toBe('1 phút 25 giây');
    expect(outputFields({ durationMs: 11300, sourceBytes: 12.3 * 1024 * 1024 }, vi, 'vi')).toEqual([
      { label: vi('workflow.output.field.duration'), value: '11,3 giây' },
      { label: 'Source', value: '12,3 MB' },
    ]);
  });

  it('groups thousands in vi durations and sizes', () => {
    const vi = i18n.getFixedT('vi');

    expect(stepDuration(step({ output: '{"durationSeconds": 3600}' }), vi, 'vi')).toBe('3.600 giây');
    expect(outputFields({ sourceBytes: 1500 * 1024 * 1024 }, vi, 'vi')).toEqual([
      { label: 'Source', value: '1.500,0 MB' },
    ]);
  });
});

describe('formatTime', () => {
  it('formats ISO string to time string', () => {
    const formatted = formatTime('2026-10-17T10:30:45.000Z', 'en');
    expect(formatted).not.toBeNull();
    expect(formatted).toMatch(/\d{1,2}:\d{2}:\d{2}/);
  });

  it('returns null for undefined or invalid input', () => {
    expect(formatTime(undefined, 'en')).toBeNull();
    expect(formatTime('invalid', 'en')).toBeNull();
  });
});
