import type { ChatMessage } from '@flow-cortex/api-client';
import { Markdown } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import { readCard, readRunId } from './cards/cardModel';
import { ConfirmCard } from './cards/ConfirmCard';
import { RunCard } from './cards/RunCard';
import { chatProblem } from './chatProblem';
import { useRetryMessage } from './useConversations';

function Speaker({ role }: Readonly<{ role: 'user' | 'assistant' }>) {
  const { t } = useTranslation();
  return (
    <span className="chat-message__speaker">
      {role === 'user' ? t('chat.thread.you') : t('chat.thread.assistant')}
    </span>
  );
}

function FailedNotice({
  conversationId,
  messageId,
  canRetry,
}: Readonly<{ conversationId: string; messageId?: string | undefined; canRetry: boolean }>) {
  const { t } = useTranslation();
  const retry = useRetryMessage(conversationId, messageId ?? '');
  const problem = retry.isError ? chatProblem(retry.error, t) : null;
  return (
    <div className="chat-message__failed">
      <p className="chat-message__notice">{t('chat.thread.failed')}</p>
      {messageId !== undefined && canRetry && (
        <button
          type="button"
          className="chat-button"
          disabled={retry.isPending}
          onClick={() => retry.mutate()}
        >
          {t('chat.thread.tryAgain')}
        </button>
      )}
      {problem && (
        <p role="alert" className="form-error">
          {problem.message}
        </p>
      )}
    </div>
  );
}

function notice(status: string | undefined, t: ReturnType<typeof useTranslation>['t']) {
  if (status === 'stopped') return t('chat.thread.stopped');
  if (status === 'streaming') return t('chat.thread.streaming');
  return null;
}

interface MessageItemProps {
  conversationId: string;
  message: ChatMessage;
  /** The text of this answer as streamed so far, while it is still being written. */
  liveText?: string | undefined;
  /** Whether this is the last message of the thread: only then can a failed answer be retried. */
  isLast: boolean;
}

export function MessageItem({ conversationId, message, liveText, isLast }: Readonly<MessageItemProps>) {
  const { t } = useTranslation();
  if (message.role === 'user') {
    return (
      <li className="chat-message chat-message--user">
        <Speaker role="user" />
        <p className="chat-message__text">{message.body}</p>
      </li>
    );
  }
  if (message.kind === 'card' && message.id !== undefined) {
    const card = readCard(message.payload);
    if (card === null) return null;
    return (
      <li className="chat-message chat-message--assistant">
        <Speaker role="assistant" />
        <ConfirmCard conversationId={conversationId} messageId={message.id} card={card} />
      </li>
    );
  }
  if (message.kind === 'run') {
    const runId = readRunId(message.payload);
    if (runId === null) return null;
    return (
      <li className="chat-message chat-message--assistant">
        <Speaker role="assistant" />
        <RunCard runId={runId} />
      </li>
    );
  }
  const stored = message.body ?? '';
  const streamed = message.status === 'streaming' && (liveText?.length ?? 0) > stored.length;
  const body = streamed ? (liveText ?? '') : stored;
  const failed = message.status === 'failed';
  const status = failed ? null : notice(message.status, t);
  // A turn that only produced a card ends done with an empty body: nothing to bubble.
  if (body.trim() === '' && status === null && !failed) return null;
  return (
    <li className="chat-message chat-message--assistant">
      <Speaker role="assistant" />
      {body.trim() !== '' && <Markdown mode="answer">{body}</Markdown>}
      {failed && <FailedNotice conversationId={conversationId} messageId={message.id} canRetry={isLast} />}
      {status !== null && <p className="chat-message__notice">{status}</p>}
    </li>
  );
}
