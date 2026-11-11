import { isLiveRunStatus } from '../../workflow/useRunEvents';

/** The hub refuses a stream with more topics than this. */
export const MAX_STREAM_TOPICS = 8;

export interface ThreadRun {
  runId: string;
  status: string | undefined;
}

/**
 * The topics one thread subscribes to: its conversation, then the runs still changing, in thread
 * order. Live runs past the cap come back as `overflow`; the thread's reloads refresh them.
 */
export function chatTopics(
  conversationId: string,
  runs: readonly ThreadRun[],
): { topics: string[]; overflow: string[] } {
  const live = [...new Set(runs.filter((run) => isLiveRunStatus(run.status)).map((run) => run.runId))];
  const room = MAX_STREAM_TOPICS - 1;
  return {
    topics: [
      `chat:${conversationId.toLowerCase()}`,
      ...live.slice(0, room).map((runId) => `run:${runId.toLowerCase()}`),
    ],
    overflow: live.slice(room),
  };
}
