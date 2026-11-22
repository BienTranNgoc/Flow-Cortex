import type { WorkflowResponse } from '@flow-cortex/api-client';
import { metaFor, stepsFor } from './workflowMeta';

const step = (type: string, stepOrder: number) => ({
  id: `s${stepOrder}`,
  stepOrder,
  name: type,
  type,
  mode: 'inline' as const,
});

const workflow = (name: string, types: readonly string[]): WorkflowResponse => ({
  id: name,
  name,
  visibility: 'public',
  available: true,
  allowFork: false,
  forkCount: 0,
  steps: types.map((type, index) => step(type, index + 1)),
});

describe('metaFor status', () => {
  it('flags a Product Video workflow with an approval checkpoint as review required', () => {
    const meta = metaFor(workflow('Product Video', ['script', 'APPROVAL_CHECKPOINT', 'render']));
    expect(meta.statusLabel).toBe('Review required');
    expect(meta.statusTone).toBe('experimental');
  });

  it('flags a workflow with a QA review step as review required', () => {
    const meta = metaFor(workflow('Product Video', ['script', 'qa.review', 'render']));
    expect(meta.statusLabel).toBe('Review required');
    expect(meta.statusTone).toBe('experimental');
  });

  it('marks a workflow with a failing step as a failure demo', () => {
    expect(metaFor(workflow('Anything', ['echo', 'fail'])).statusLabel).toBe('Demo: failure');
  });

  it('ignores a failure-sounding name when no step fails', () => {
    const meta = metaFor(workflow('Failing Demo (deliberate)', ['echo']));
    expect(meta.statusLabel).toBe('Ready');
    expect(meta.statusTone).toBe('ready');
  });
});

describe('stepsFor kind', () => {
  it('draws both human step types as checkpoints and a failing step as a failure', () => {
    const kinds = stepsFor(
      workflow('Anything', ['echo', 'APPROVAL_CHECKPOINT', 'qa.review', 'fail']),
    ).map((view) => view.kind);
    expect(kinds).toEqual(['normal', 'checkpoint', 'checkpoint', 'failure']);
  });
});
