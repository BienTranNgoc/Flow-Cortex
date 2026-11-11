import { can, type Caller } from '../../app/permissions';

const STOPPABLE = new Set(['running', 'waiting_approval']);

/** Whether the "Stop run" action is offered: the caller holds the stop key and the run is still live. */
export function canStopRun(status: string, me: Caller | undefined): boolean {
  return STOPPABLE.has(status) && can(me, 'workflow.run.cancel');
}
