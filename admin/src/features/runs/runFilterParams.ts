import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { z } from 'zod';
import { isCalendarDay } from './dayRange';

/** The run list's filters as the address carries them; `from` and `to` are chosen days, not instants. */
export interface RunFilterParams {
  status: ListAdminRunsStatus | undefined;
  workflowId: string | undefined;
  from: string | undefined;
  to: string | undefined;
}

const workflowIdSchema = z.string().uuid();
const statusSchema = z.nativeEnum(ListAdminRunsStatus);

/** Reads the filters from the query string; a value that could not be a valid filter is dropped, not sent. */
export function parseRunFilterParams(params: URLSearchParams): RunFilterParams {
  const status = statusSchema.safeParse(params.get('status'));
  const workflowId = workflowIdSchema.safeParse(params.get('workflowId'));
  const from = params.get('from') ?? undefined;
  const to = params.get('to') ?? undefined;
  return {
    status: status.success ? status.data : undefined,
    workflowId: workflowId.success ? workflowId.data : undefined,
    from: isCalendarDay(from) ? from : undefined,
    to: isCalendarDay(to) ? to : undefined,
  };
}

/** Returns `params` with `changes` applied; an empty value removes its key. */
export function withRunFilterParams(
  params: URLSearchParams,
  changes: Partial<Record<keyof RunFilterParams, string>>,
): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const [key, value] of Object.entries(changes)) {
    if (value) next.set(key, value);
    else next.delete(key);
  }
  return next;
}
