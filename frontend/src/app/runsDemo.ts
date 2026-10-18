export interface DemoRun {
  readonly id: string;
  readonly workflowName: string;
  readonly workflowId: string;
  readonly status: 'success' | 'failure' | 'waiting' | 'running';
  readonly startedAt: string;
  readonly finishedAt?: string;
  readonly errorMessage?: string;
  readonly etaSeconds: number;
  readonly trigger: 'manual' | 'webhook' | 'schedule';
  readonly runHash: string;
  readonly expectedDurationSec: number;
}

interface MakeArgs {
  readonly id: string;
  readonly workflowId: string;
  readonly workflowName: string;
  readonly status: DemoRun['status'];
  readonly startedMinutesAgo: number;
  readonly trigger: DemoRun['trigger'];
  readonly expectedDurationSec: number;
  readonly errorMessage?: string;
}

const make = (args: MakeArgs): DemoRun => {
  const { id, workflowId, workflowName, status, startedMinutesAgo, trigger, expectedDurationSec, errorMessage } = args;
  const started = new Date(Date.now() - startedMinutesAgo * 60_000);
  const finished = status === 'running' || status === 'waiting'
    ? undefined
    : new Date(started.getTime() + expectedDurationSec * 1000).toISOString();
  const runHash = id.replace(/[^a-z0-9]/gi, '').slice(-6).toLowerCase();
  return {
    id,
    workflowId,
    workflowName,
    status,
    startedAt: started.toISOString(),
    ...(finished ? { finishedAt: finished } : {}),
    ...(errorMessage ? { errorMessage } : {}),
    etaSeconds: expectedDurationSec,
    trigger,
    runHash,
    expectedDurationSec,
  };
};

export const DEMO_RUNS: readonly DemoRun[] = [
  make({ id: 'run-echo-1', workflowId: 'wf-echo', workflowName: 'Echo Demo', status: 'success', startedMinutesAgo: 5, trigger: 'manual', expectedDurationSec: 32 }),
  make({ id: 'run-fail-1', workflowId: 'wf-fail', workflowName: 'Deliberate Failure', status: 'failure', startedMinutesAgo: 12, trigger: 'schedule', expectedDurationSec: 18, errorMessage: 'Step "throw" failed: deliberate failure for testing.' }),
  make({ id: 'run-product-1', workflowId: 'wf-product-video', workflowName: 'Product Video', status: 'running', startedMinutesAgo: 2, trigger: 'webhook', expectedDurationSec: 180 }),
  make({ id: 'run-tiktok-1', workflowId: 'wf-douyin', workflowName: 'Repurpose for Douyin', status: 'waiting', startedMinutesAgo: 8, trigger: 'manual', expectedDurationSec: 90 }),
  make({ id: 'run-probe-1', workflowId: 'wf-probe', workflowName: 'Probe a Video', status: 'success', startedMinutesAgo: 24, trigger: 'webhook', expectedDurationSec: 22 }),
];
