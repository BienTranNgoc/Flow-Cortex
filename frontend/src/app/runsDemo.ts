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

const make = (
  id: string,
  workflowId: string,
  workflowName: string,
  status: DemoRun['status'],
  startedMinutesAgo: number,
  trigger: DemoRun['trigger'],
  expectedDurationSec: number,
  errorMessage?: string,
): DemoRun => {
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
  make('run-echo-1', 'wf-echo', 'Echo Demo', 'success', 5, 'manual', 32),
  make('run-fail-1', 'wf-fail', 'Deliberate Failure', 'failure', 12, 'schedule', 18, 'Step "throw" failed: deliberate failure for testing.'),
  make('run-product-1', 'wf-product-video', 'Product Video', 'running', 2, 'webhook', 180),
  make('run-tiktok-1', 'wf-douyin', 'Repurpose for Douyin', 'waiting', 8, 'manual', 90),
  make('run-probe-1', 'wf-probe', 'Probe a Video', 'success', 24, 'webhook', 22),
];
