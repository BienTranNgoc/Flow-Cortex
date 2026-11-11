import type { Attachment } from '@flow-cortex/api-client';
import { strippedLength } from '@flow-cortex/ui-kit';
import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ChatProblem } from './chatProblem';
import { isSubmitKey, nextSendKey, type SendKey } from './composerKeys';
import { AttachmentPicker } from './stream/AttachmentPicker';
import { useAttachments } from './stream/useAttachments';

/** Matches the backend, which counts code points of the stripped message. */
export const MAX_MESSAGE_LENGTH = 4000;

/** At most this many images travel with one message. */
const MAX_ATTACHMENTS = 10;

interface ComposerProps {
  /** The assistant is mid-answer: sending waits for it. */
  busy: boolean;
  sending: boolean;
  problem: ChatProblem | null;
  /** Resolves once the server accepted the message; rejects on a refusal (shown through `problem`). */
  onSend: (text: string, key: string, attachments: Attachment[]) => Promise<unknown>;
  /** Ends the answer being written; the button shows only while `busy`. */
  onStop: () => void;
  stopping: boolean;
  /** Puts the caret in the box when it first shows. */
  focusOnMount?: boolean;
}

export function Composer({ busy, sending, problem, onSend, onStop, stopping, focusOnMount = false }: Readonly<ComposerProps>) {
  const { t } = useTranslation();
  const inputId = useId();
  const inputRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (focusOnMount) inputRef.current?.focus();
  }, [focusOnMount]);
  const counterId = useId();
  const [draft, setDraft] = useState('');
  const sendKey = useRef<SendKey | null>(null);
  const attachments = useAttachments(MAX_ATTACHMENTS);
  const length = strippedLength(draft);
  const over = length > MAX_MESSAGE_LENGTH;
  const blocked = busy || sending || attachments.uploading;
  const canSend = draft.trim() !== '' && !over && !blocked;

  const submit = (event: FormEvent | KeyboardEvent) => {
    event.preventDefault();
    if (!canSend) return;
    const text = draft.trim();
    const sent = attachments.ready;
    // The same text with the same images is the same click retried; anything else is a new message.
    const identity = `${text}\n${sent.map((item) => item.uploadId).join(',')}`;
    sendKey.current = nextSendKey(sendKey.current, identity);
    onSend(text, sendKey.current.key, sent).then(
      () => {
        sendKey.current = null;
        setDraft('');
        attachments.clear();
      },
      () => {},
    );
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (isSubmitKey({ key: event.key, shiftKey: event.shiftKey, isComposing: event.nativeEvent.isComposing })) {
      submit(event);
    }
  };

  return (
    <form className="chat-composer" onSubmit={submit}>
      {problem && (
        <div role="alert" className="chat-composer__problem">
          <p>{problem.message}</p>
          {problem.resume && <p>{problem.resume}</p>}
        </div>
      )}
      <label className="chat-sr-only" htmlFor={inputId}>
        {t('chat.composer.label')}
      </label>
      <textarea
        id={inputId}
        ref={inputRef}
        className="chat-composer__input"
        rows={2}
        value={draft}
        placeholder={t('chat.composer.placeholder')}
        aria-describedby={counterId}
        aria-invalid={over || undefined}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
      />
      <AttachmentPicker
        attachments={attachments}
        max={MAX_ATTACHMENTS}
        label={t('chat.composer.attach')}
        disabled={sending}
      />
      <div className="chat-composer__row">
        <span
          id={counterId}
          className={over ? 'chat-counter chat-counter--over' : 'chat-counter'}
        >
          {over
            ? t('chat.composer.over', { max: MAX_MESSAGE_LENGTH })
            : t('chat.composer.counter', { length, max: MAX_MESSAGE_LENGTH })}
        </span>
        {busy && (
          <button
            type="button"
            className="chat-button"
            aria-busy={stopping}
            onClick={() => !stopping && onStop()}
          >
            {stopping ? t('chat.composer.stopping') : t('chat.composer.stop')}
          </button>
        )}
        <button type="submit" className="chat-button chat-button--primary" disabled={!canSend}>
          {sending ? t('chat.composer.sending') : t('chat.composer.send')}
        </button>
      </div>
    </form>
  );
}
