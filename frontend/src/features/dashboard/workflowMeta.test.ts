import type { WorkflowResponse } from '@flow-cortex/api-client';
import { metaFor } from './workflowMeta';

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
  isShared: true,
  steps: types.map((type, index) => step(type, index + 1)),
});

describe('metaFor status', () => {
  it('flags a Product Video workflow with an approval checkpoint as review required', () => {
    const meta = metaFor(workflow('Product Video', ['script', 'APPROVAL_CHECKPOINT', 'render']));
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
