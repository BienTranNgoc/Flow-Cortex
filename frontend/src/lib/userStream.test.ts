import type { EventStreamOptions, StreamEvent } from '@flow-cortex/api-client';
import { followUserStream } from './userStream';

interface FakeStream {
  options: EventStreamOptions;
  closed: boolean;
}

const streams = vi.hoisted(() => ({ opened: [] as FakeStream[] }));

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: (options: EventStreamOptions) => {
    const stream: FakeStream = { options, closed: false };
    streams.opened.push(stream);
    return {
      close: () => {
        stream.closed = true;
      },
    };
  },
}));

function openStreams(): FakeStream[] {
  return streams.opened.filter((stream) => !stream.closed);
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

function follower() {
  const opens = vi.fn();
  const events: StreamEvent[] = [];
  return {
    opens,
    events,
    follower: { onOpen: opens, onEvent: (e: StreamEvent) => events.push(e) },
  };
}

const unfollowers: Array<() => void> = [];
function follow(target: ReturnType<typeof follower>['follower']) {
  const unfollow = followUserStream(target);
  unfollowers.push(unfollow);
  return unfollow;
}

describe('followUserStream', () => {
  beforeEach(() => {
    streams.opened.length = 0;
    setVisibility('visible');
  });
  afterEach(() => {
    unfollowers.splice(0).forEach((unfollow) => unfollow());
  });

  it('opensOneStreamForTwoFollowers', () => {
    follow(follower().follower);
    follow(follower().follower);

    expect(openStreams()).toHaveLength(1);
    expect(openStreams()[0]?.options.topics).toEqual(['user']);
  });

  it('opensNothingWhileHidden', () => {
    setVisibility('hidden');

    follow(follower().follower);

    expect(openStreams()).toHaveLength(0);
  });

  it('closesOnHiddenAndReopensOnVisibleWhileAFollowerRemains', () => {
    follow(follower().follower);

    setVisibility('hidden');
    expect(openStreams()).toHaveLength(0);

    setVisibility('visible');
    expect(openStreams()).toHaveLength(1);
    expect(streams.opened).toHaveLength(2);
  });

  it('closesWhenTheLastFollowerLeavesAndNotBefore', () => {
    const leaveFirst = follow(follower().follower);
    const leaveSecond = follow(follower().follower);

    leaveFirst();
    expect(openStreams()).toHaveLength(1);

    leaveFirst();
    expect(openStreams()).toHaveLength(1);

    leaveSecond();
    expect(openStreams()).toHaveLength(0);

    setVisibility('hidden');
    setVisibility('visible');
    expect(openStreams()).toHaveLength(0);
  });

  it('everyFollowerGetsEveryEventAndEveryOpen', () => {
    const first = follower();
    const second = follower();
    follow(first.follower);
    follow(second.follower);
    const event: StreamEvent = { event: 'notifications.changed', data: '{}', id: null };

    openStreams()[0]?.options.onOpen?.();
    openStreams()[0]?.options.onEvent(event);

    expect(first.opens).toHaveBeenCalledTimes(1);
    expect(second.opens).toHaveBeenCalledTimes(1);
    expect(first.events).toEqual([event]);
    expect(second.events).toEqual([event]);
  });

  it('aFollowerJoiningAnOpenStreamDoesNotReopenIt', () => {
    follow(follower().follower);
    const first = openStreams()[0];

    follow(follower().follower);

    expect(streams.opened).toHaveLength(1);
    expect(openStreams()[0]).toBe(first);
  });

  it('aLeftFollowerHearsNothingMore', () => {
    const leaving = follower();
    const staying = follower();
    const leave = follow(leaving.follower);
    follow(staying.follower);
    const options = openStreams()[0]?.options;

    leave();
    options?.onOpen?.();

    expect(leaving.opens).not.toHaveBeenCalled();
    expect(staying.opens).toHaveBeenCalledTimes(1);
  });

  it('aClosedStreamNeverDeliversToTheNextOne', () => {
    const watching = follower();
    follow(watching.follower);
    const stale = openStreams()[0]?.options;

    setVisibility('hidden');
    setVisibility('visible');
    stale?.onOpen?.();
    stale?.onEvent({ event: 'resync', data: '{}', id: null });

    expect(watching.opens).not.toHaveBeenCalled();
    expect(watching.events).toEqual([]);
  });
});
