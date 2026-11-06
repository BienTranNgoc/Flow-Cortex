import type { SupportAssistantHistoryMessage } from '@flow-cortex/api-client';
import { Markdown } from '@flow-cortex/ui-kit';
import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { LoadFailed } from './GuidesTab';
import { ContactLine } from './HomeTab';
import { SuspendedNotice } from './RequestForm';
import { problemMessage } from './supportProblems';
import { useSupportPanel, type RequestPrefill } from './supportPanelState';
import {
  isMinuteCap,
  MAX_QUESTION_LENGTH,
  questionLength,
  resumeTime,
  useAskAssistant,
  useAssistantAvailability,
  useAssistantConversation,
} from './useAssistant';
import { isSuspended } from './useRequests';
import { supportProblemCode } from './useSupportKb';
import './assistant.css';

const SERVER_OFF_CODES = new Set(['assistant-paused', 'assistant-disabled']);

/** A refused question that goes back into the box instead of staying in the log as unsent. */
function returnsToBox(error: unknown): boolean {
  const code = supportProblemCode(error) ?? '';
  return (
    code === 'question-too-long' ||
    SERVER_OFF_CODES.has(code) ||
    isSuspended(error) ||
    (code === 'assistant-quota' && isMinuteCap(error))
  );
}

function Speaker({ name }: Readonly<{ name: string }>) {
  return <span className="assistant-visually-hidden">{name}: </span>;
}

/** What follows a reply: escalation for a member, sign-in and the contact line for a guest. */
function ReplyOffers({
  answered,
  prefill,
}: Readonly<{ answered: boolean; prefill: RequestPrefill }>) {
  const { t } = useTranslation();
  const { dispatch, member } = useSupportPanel();
  if (member) {
    return (
      <button
        type="button"
        className={answered ? 'assistant-link' : 'support-button'}
        onClick={() => dispatch({ type: 'startRequest', prefill })}
      >
        {answered ? t('support.assistant.escalate') : t('support.assistant.newRequest')}
      </button>
    );
  }
  return (
    <>
      {!answered && (
        <div className="assistant-offers">
          <Link className="support-button assistant-offers__link" to="/login">
            {t('support.assistant.signIn')}
          </Link>
          <Link className="support-button assistant-offers__link" to="/register">
            {t('support.assistant.register')}
          </Link>
        </div>
      )}
      <ContactLine />
    </>
  );
}

function AssistantMessage({
  message,
  prefill,
}: Readonly<{ message: SupportAssistantHistoryMessage; prefill: RequestPrefill }>) {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const outcome = message.outcome ?? 'answered';
  // Messages saved before sources were recorded carry none.
  const sources = message.sources ?? [];

  return (
    <li className="assistant-message assistant-message--assistant">
      <Speaker name={t('support.assistant.assistantName')} />
      {outcome === 'answered' ? (
        <Markdown mode="answer">{message.text}</Markdown>
      ) : (
        <p className="assistant-message__fixed">
          {outcome === 'blocked' ? t('support.assistant.blocked') : t('support.assistant.noAnswer')}
        </p>
      )}
      {outcome === 'answered' && sources.length > 0 && (
        <div className="assistant-sources">
          <span className="assistant-sources__label">{t('support.assistant.sources')}</span>
          <ul className="assistant-sources__list">
            {sources.map((source) => (
              <li key={source.slug}>
                <button
                  type="button"
                  className="assistant-chip"
                  onClick={() => dispatch({ type: 'openArticle', slug: source.slug })}
                >
                  {source.title}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      <ReplyOffers answered={outcome === 'answered'} prefill={prefill} />
    </li>
  );
}

/** The question a reply answered, and its conversation, for a request filed from that reply. */
function requestPrefill(
  messages: readonly SupportAssistantHistoryMessage[],
  replyIndex: number,
  conversationId: string | undefined,
): RequestPrefill {
  let asked = '';
  for (let i = replyIndex - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (message?.role === 'user') {
      asked = message.text;
      break;
    }
  }
  return { description: asked, ...(conversationId ? { aiConversationId: conversationId } : {}) };
}

function UserMessage({ text }: Readonly<{ text: string }>) {
  const { t } = useTranslation();
  return (
    <li className="assistant-message assistant-message--user">
      <Speaker name={t('support.assistant.you')} />
      {text}
    </li>
  );
}

function UnavailableNotice() {
  const { t } = useTranslation();
  const { dispatch, member } = useSupportPanel();
  return (
    <div className="support-state assistant-notice" role="status">
      <p>{t('support.assistant.unavailableNotice')}</p>
      {member ? (
        <button
          type="button"
          className="support-button"
          onClick={() => dispatch({ type: 'selectTab', tab: 'guides' })}
        >
          {t('support.assistant.toGuides')}
        </button>
      ) : (
        <Link className="support-button assistant-offers__link" to="/login">
          {t('support.assistant.toSignIn')}
        </Link>
      )}
    </div>
  );
}

function SendFailed({ error, onRetry }: Readonly<{ error: unknown; onRetry: () => void }>) {
  const { t } = useTranslation();
  const { member } = useSupportPanel();
  const code = supportProblemCode(error);

  if (code === 'assistant-quota') {
    const resumeAt = resumeTime(error);
    return (
      <div className="support-state" role="alert">
        <p>
          {isMinuteCap(error)
            ? t('support.assistant.tooFast', { resumeAt })
            : problemMessage(code, { resumeAt }, t)}
        </p>
        {!member && !isMinuteCap(error) && (
          <Link className="support-button assistant-offers__link" to="/login">
            {t('support.assistant.signInForMore')}
          </Link>
        )}
      </div>
    );
  }
  const final = code === 'question-too-long' || code === 'support-disabled';
  const message =
    final || code === 'assistant-unavailable'
      ? problemMessage(code, {}, t)
      : t('support.assistant.sendFailed');
  return (
    <div className="support-state" role="alert">
      <p>{message}</p>
      {!final && (
        <button type="button" className="support-button" onClick={onRetry}>
          {t('support.assistant.retry')}
        </button>
      )}
    </div>
  );
}

/** Assistant: the conversation log, the reply state, and the question box under it. */
export function AssistantTab() {
  const { t } = useTranslation();
  const { state, dispatch, member } = useSupportPanel();
  const availability = useAssistantAvailability();
  const { query: history, startOver } = useAssistantConversation(member);
  const ask = useAskAssistant(member);
  const [draft, setDraft] = useState(() => state.question ?? '');
  const inputId = useId();
  const counterId = useId();
  const logRef = useRef<HTMLOListElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const conversation = history.data ?? null;
  const messages = conversation?.messages ?? [];
  const serverOff = SERVER_OFF_CODES.has(supportProblemCode(ask.error) ?? '');
  const suspended = isSuspended(history.error) || isSuspended(ask.error);
  const open = availability === 'on' && !serverOff && !suspended;
  const length = questionLength(draft);
  // A send before the history arrives would fork a new conversation from the saved one.
  const canSend =
    open && !history.isPending && !ask.isPending && length >= 1 && length <= MAX_QUESTION_LENGTH;
  const unsent =
    ask.isPending || (ask.isError && !returnsToBox(ask.error))
      ? ask.variables?.question
      : undefined;

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [messages.length, unsent]);

  const send = (question: string) =>
    ask.mutate(
      { question, conversationId: conversation?.conversationId ?? null },
      {
        onError: (error) => {
          if (returnsToBox(error)) setDraft(question);
        },
      },
    );

  const handedOver = state.question;
  const historyReady = !history.isPending;
  useEffect(() => {
    if (handedOver === null || availability === 'checking' || !historyReady) return;
    dispatch({ type: 'questionSent' });
    // While the assistant is off the question stays in the box instead of being lost.
    if (availability === 'on') {
      setDraft('');
      send(handedOver);
    }
    // `send` is rebuilt every render; the handed-over question is sent once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handedOver, availability, historyReady, dispatch]);

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (!canSend) return;
    send(draft.trim());
    setDraft('');
    inputRef.current?.focus();
  };

  const retry = (question: string) => {
    send(question);
    inputRef.current?.focus();
  };

  const onInputKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) submit(event);
  };

  const newConversation = () => {
    ask.reset();
    startOver();
    if (open) inputRef.current?.focus();
    else logRef.current?.focus();
  };

  return (
    <div className="assistant">
      {messages.length === 0 && unsent === undefined && history.isSuccess && (
        <p className="assistant-intro">{t('support.assistant.intro')}</p>
      )}
      {history.isPending && (
        <p className="support-state" role="status">
          {t('support.common.loading')}
        </p>
      )}
      {history.isError &&
        (isSuspended(history.error) ? (
          <SuspendedNotice />
        ) : (
          <LoadFailed error={history.error} onRetry={() => history.refetch()} />
        ))}
      <ol
        ref={logRef}
        className="assistant-log"
        role="log"
        aria-live="polite"
        aria-label={t('support.assistant.logLabel')}
        tabIndex={0}
      >
        {messages.map((message, index) =>
          message.role === 'user' ? (
            <UserMessage key={`${message.createdAt}-${index}`} text={message.text} />
          ) : (
            <AssistantMessage
              key={`${message.createdAt}-${index}`}
              message={message}
              prefill={requestPrefill(messages, index, conversation?.conversationId)}
            />
          ),
        )}
        {unsent !== undefined && <UserMessage text={unsent} />}
      </ol>
      {ask.isPending && (
        <p className="assistant-typing" role="status">
          {t('support.assistant.typing')}
        </p>
      )}
      {ask.isError &&
        !serverOff &&
        (isSuspended(ask.error) ? (
          !isSuspended(history.error) && <SuspendedNotice />
        ) : (
          <SendFailed
            error={ask.error}
            onRetry={() => ask.variables && retry(ask.variables.question)}
          />
        ))}
      {(availability === 'off' || serverOff) && <UnavailableNotice />}
      {messages.length > 0 && (
        <button type="button" className="assistant-link" onClick={newConversation}>
          {t('support.assistant.newConversation')}
        </button>
      )}
      <form className="assistant-form" onSubmit={submit}>
        <label className="assistant-form__label" htmlFor={inputId}>
          {t('support.assistant.inputLabel')}
        </label>
        <textarea
          ref={inputRef}
          id={inputId}
          className="assistant-form__input"
          rows={3}
          value={draft}
          placeholder={t('support.assistant.inputPlaceholder')}
          disabled={!open}
          aria-describedby={counterId}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onInputKey}
        />
        <div className="assistant-form__row">
          <span
            id={counterId}
            className={
              length > MAX_QUESTION_LENGTH
                ? 'assistant-counter assistant-counter--over'
                : 'assistant-counter'
            }
          >
            {t('support.assistant.counter', { length, max: MAX_QUESTION_LENGTH })}
          </span>
          <button
            type="submit"
            className="support-button support-button--primary"
            disabled={!canSend && !ask.isPending}
            aria-disabled={ask.isPending || undefined}
          >
            {t('support.assistant.send')}
          </button>
        </div>
      </form>
    </div>
  );
}
