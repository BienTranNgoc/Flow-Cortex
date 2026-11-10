import { MAX_STREAM_TOPICS, chatTopics, type ThreadRun } from './topicSet';

const CONVERSATION = 'C0NVERSATI0N-ID';
const run = (n: number, status: string): ThreadRun => ({ runId: `RUN-${n}`, status });

describe('chatTopics', () => {
  it('names the conversation topic in lower case, alone when no run is live', () => {
    expect(chatTopics(CONVERSATION, [run(1, 'completed')]).topics).toEqual(['chat:c0nversati0n-id']);
  });

  it('adds only the live runs when 9 run cards hold 2 live ones', () => {
    const runs = [
      ...Array.from({ length: 7 }, (_, i) => run(i, i % 2 ? 'error' : 'completed')),
      run(7, 'running'),
      run(8, 'waiting_approval'),
    ];

    expect(chatTopics(CONVERSATION, runs).topics).toEqual([
      'chat:c0nversati0n-id',
      'run:run-7',
      'run:run-8',
    ]);
  });

  it('never exceeds the hub cap and reports the live runs left out', () => {
    const runs = Array.from({ length: 10 }, (_, i) => run(i, 'running'));
    const { topics, overflow } = chatTopics(CONVERSATION, runs);

    expect(topics).toHaveLength(MAX_STREAM_TOPICS);
    expect(topics[0]).toBe('chat:c0nversati0n-id');
    expect(overflow).toEqual(['RUN-7', 'RUN-8', 'RUN-9']);
  });

  it('changes when a live run ends', () => {
    const before = chatTopics(CONVERSATION, [run(1, 'running'), run(2, 'running')]).topics;
    const after = chatTopics(CONVERSATION, [run(1, 'running'), run(2, 'completed')]).topics;

    expect(before).toEqual(['chat:c0nversati0n-id', 'run:run-1', 'run:run-2']);
    expect(after).toEqual(['chat:c0nversati0n-id', 'run:run-1']);
  });

  it('lists a run once and skips a run whose status is not known yet', () => {
    const runs = [run(1, 'running'), run(1, 'running'), { runId: 'RUN-2', status: undefined }];

    expect(chatTopics(CONVERSATION, runs).topics).toEqual(['chat:c0nversati0n-id', 'run:run-1']);
  });
});
