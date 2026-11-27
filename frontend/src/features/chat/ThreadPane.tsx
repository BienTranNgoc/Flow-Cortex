import { useQueries, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { isLiveRunStatus } from '../workflow/useRunEvents';
import { threadRunIds } from './cards/cardModel';
import { runQueryOptions } from './cards/runQuery';
import { chatProblem } from './chatProblem';
import { chatTopics } from './stream/topicSet';
import { useChatStream } from './stream/useChatStream';
import { useStopTurn } from './stream/useStopTurn';
import { Composer } from './Composer';
import { MessageItem } from './MessageItem';
import { invalidateConversationViews, useMessages, useSendMessage } from './useConversations';

interface ThreadPaneProps {
  conversationId: string;
  chatEnabled: boolean;
}

function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="chat-state" role="alert">
      <p>{t('chat.problems.notFound')}</p>
      <Link to="/chat" className="chat-link">
        {t('chat.problems.startNew')}
      </Link>
    </div>
  );
}

export function ThreadPane({ conversationId, chatEnabled }: Readonly<ThreadPaneProps>) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const messages = useMessages(conversationId);
  const send = useSendMessage(conversationId);
  const stop = useStopTurn(conversationId);
  const endRef = useRef<HTMLDivElement>(null);
  const items = useMemo(() => messages.data ?? [], [messages.data]);
  const lastIndex = items.length - 1;
  const lastStatus = items[lastIndex]?.status;

  const runIds = useMemo(() => threadRunIds(items), [items]);
  const runs = useQueries({
    queries: runIds.map(runQueryOptions),
    combine: (results) =>
      results.map((result, index) => ({
        runId: runIds[index] ?? '',
        status: result.data?.status,
        hasRunningStep: result.data?.steps?.some((step) => step.status === 'running') ?? false,
      })),
  });
  const { topics } = useMemo(() => chatTopics(conversationId, runs), [conversationId, runs]);
  const liveRunIds = useMemo(
    () =>
      runs
        .filter((run) => isLiveRunStatus(run.status) || Boolean(run.hasRunningStep))
        .map((run) => run.runId),
    [runs],
  );
  const liveRunsRef = useRef(liveRunIds);
  liveRunsRef.current = liveRunIds;
  const refetchRun = useCallback(
    (runId: string) => void queryClient.refetchQueries({ queryKey: ['runs', runId], exact: true }),
    [queryClient],
  );
  const refetchMessages = messages.refetch;
  // Runs past the hub's topic cap get no events, so every reload refreshes all the runs still changing.
  const reload = useCallback(
    () =>
      Promise.all([
        refetchMessages(),
        ...liveRunsRef.current.map((runId) => queryClient.refetchQueries({ queryKey: ['runs', runId], exact: true })),
      ]),
    [refetchMessages, queryClient],
  );
  const live = useChatStream({ topics, messages: messages.data, reload, refetchRun });

  const lastId = items[lastIndex]?.id;
  const streamedLength = lastId === undefined ? 0 : (live[lastId]?.length ?? 0);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: 'end' });
  }, [items.length, lastStatus, streamedLength]);

  // The answer's terminal write sets the row preview without bumping last activity, and no list
  // write follows it, so the sidebar's preview stays the question until this refresh.
  const last = items[lastIndex];
  const answerId = last?.role === 'assistant' ? last.id : undefined;
  const answerStatus = last?.role === 'assistant' ? last.status : undefined;
  const previousAnswer = useRef<{ id: string | undefined; status: string | undefined }>({ id: undefined, status: undefined });
  useEffect(() => {
    const before = previousAnswer.current;
    previousAnswer.current = { id: answerId, status: answerStatus };
    if (before.status === 'streaming' && before.id === answerId && answerStatus && answerStatus !== 'streaming') {
      void invalidateConversationViews(queryClient);
    }
  }, [answerId, answerStatus, queryClient]);

  const loadProblem = messages.error ? chatProblem(messages.error, t) : null;
  const sendProblem = send.error ? chatProblem(send.error, t) : null;
  if (loadProblem?.kind === 'not-found' || sendProblem?.kind === 'not-found') return <NotFound />;

  const chatOff = !chatEnabled || sendProblem?.kind === 'disabled';
  const answering = items[lastIndex]?.role === 'assistant' && items[lastIndex]?.status === 'streaming';

  return (
    <>
      <div className="chat-thread">
        {messages.isPending && <p role="status">{t('chat.thread.loading')}</p>}
        {loadProblem && (
          <div className="chat-state" role="alert">
            <p>{t('chat.thread.loadFailed')}</p>
            <button type="button" className="chat-button" onClick={() => void messages.refetch()}>
              {t('chat.thread.retry')}
            </button>
          </div>
        )}
        {messages.isSuccess && items.length === 0 && (
          <p className="chat-thread__empty">{t('chat.thread.empty')}</p>
        )}
        <div role="log" aria-live="polite" aria-label={t('chat.thread.log')}>
          <ol className="chat-log">
            {items.map((message, index) => (
              <MessageItem
                key={message.id ?? message.seq}
                isLast={index === lastIndex}
                conversationId={conversationId}
                message={message}
                liveText={message.id === undefined ? undefined : live[message.id]}
              />
            ))}
          </ol>
        </div>
        <div ref={endRef} />
      </div>
      {chatOff ? (
        <p className="chat-off" role="status">
          {t('chat.problems.off')}
        </p>
      ) : (
        <Composer
          busy={answering}
          sending={send.isPending}
          problem={sendProblem}
          onSend={(text, key, attachments) => send.mutateAsync({ text, key, attachments })}
          onStop={() => {
            const answering = items[lastIndex]?.id;
            if (answering) stop.mutate(answering);
          }}
          stopping={stop.isPending}
        />
      )}
    </>
  );
}
