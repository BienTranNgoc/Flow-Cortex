import { openEventStream, type ChatMessage, type StreamEvent } from '@flow-cortex/api-client';
import { useEffect, useReducer, useRef, useState } from 'react';
import { createRefetchCoalescer, type RefetchCoalescer } from '../../workflow/refetchCoalescer';
import { initialThreadState, threadReducer } from './threadReducer';

interface ChatStreamOptions {
  /** From `chatTopics`; the stream is reopened when this list changes. */
  topics: readonly string[];
  messages: readonly ChatMessage[] | undefined;
  /** Reloads the stored thread and the runs still changing; called on every (re)connect, `resync`, `chat.updated` and missed delta. */
  reload: () => Promise<unknown>;
  refetchRun: (runId: string) => void;
}

const UNCONNECTED_RELOAD_MS = 5_000;

function readJson(data: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(data);
    return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * Holds the thread's hub stream. Returns the text of every answer still streaming; the stored
 * messages stay the truth, so a reload replaces whatever the deltas built.
 */
export function useChatStream({ topics, messages, reload, refetchRun }: ChatStreamOptions): Record<string, string> {
  const [state, dispatch] = useReducer(threadReducer, initialThreadState);
  const reloadRef = useRef(reload);
  reloadRef.current = reload;
  const refetchRunRef = useRef(refetchRun);
  refetchRunRef.current = refetchRun;
  const [opened, setOpened] = useState(false);
  const coalescer = useRef<RefetchCoalescer | null>(null);
  const topicKey = topics.join(',');

  useEffect(() => {
    if (messages) dispatch({ type: 'loaded', messages });
  }, [messages]);

  useEffect(() => {
    if (state.gaps > 0) coalescer.current?.request();
  }, [state.gaps]);

  useEffect(() => {
    const reloads = createRefetchCoalescer(() => reloadRef.current());
    coalescer.current = reloads;
    const onEvent = (event: StreamEvent) => {
      if (event.event === 'chat.delta') {
        const data = readJson(event.data);
        if (
          typeof data?.messageId === 'string' &&
          typeof data.offset === 'number' &&
          typeof data.text === 'string'
        ) {
          dispatch({ type: 'delta', messageId: data.messageId, offset: data.offset, text: data.text });
        }
      } else if (event.event === 'run.updated') {
        const runId = readJson(event.data)?.runId;
        if (typeof runId === 'string') refetchRunRef.current(runId);
        else reloads.request();
      } else if (event.event === 'chat.updated' || event.event === 'resync') {
        reloads.request();
      }
    };
    const stream = openEventStream({
      topics: topicKey.split(','),
      onEvent,
      onOpen: () => {
        setOpened(true);
        reloads.request();
      },
    });
    return () => {
      setOpened(false);
      reloads.stop();
      coalescer.current = null;
      stream.close();
    };
  }, [topicKey]);

  // A stream the server refuses (e.g. past the per-user cap) never delivers the finished answer.
  const waiting = !opened && (messages?.some((message) => message.status === 'streaming') ?? false);
  useEffect(() => {
    if (!waiting) return;
    const timer = setInterval(() => void reloadRef.current(), UNCONNECTED_RELOAD_MS);
    return () => clearInterval(timer);
  }, [waiting]);

  return state.live;
}
