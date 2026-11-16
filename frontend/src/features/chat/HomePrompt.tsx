import { useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { newUuidV4 } from '../support/uuid';
import { chatProblem } from './chatProblem';
import { isSubmitKey, nextSendKey, type SendKey } from './composerKeys';
import { MAX_MESSAGE_LENGTH } from './Composer';
import { useCreateConversation } from './useConversations';
import { strippedLength } from '@flow-cortex/ui-kit';

/** Home's "What do you want to create?" box: creates a conversation with this as its first message. */
export function HomePrompt() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreateConversation();
  const inputId = useId();
  const [draft, setDraft] = useState('');
  const sendKey = useRef<SendKey | null>(null);
  const problem = create.isError ? chatProblem(create.error, t) : null;
  const text = draft.trim();
  const canSend = text !== '' && strippedLength(draft) <= MAX_MESSAGE_LENGTH && !create.isPending;

  const submit = (event: FormEvent | KeyboardEvent) => {
    event.preventDefault();
    if (!canSend) return;
    sendKey.current = nextSendKey(sendKey.current, text, newUuidV4);
    create.mutate(
      { firstMessage: { text }, key: sendKey.current.key },
      { onSuccess: (created) => void navigate(`/chat/${created.id}`) },
    );
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (isSubmitKey({ key: event.key, shiftKey: event.shiftKey, isComposing: event.nativeEvent.isComposing })) {
      submit(event);
    }
  };

  return (
    <form className="home-prompt" onSubmit={submit}>
      <label className="home-prompt__label" htmlFor={inputId}>
        {t('common.home.promptLabel')}
      </label>
      <div className="home-prompt__row">
        <textarea
          id={inputId}
          className="home-prompt__input"
          rows={2}
          value={draft}
          placeholder={t('common.home.promptPlaceholder')}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="cortex-btn cortex-btn--primary home-prompt__send" disabled={!canSend}>
          {create.isPending ? t('common.home.promptSending') : t('common.home.promptSend')}
        </button>
      </div>
      {problem && (
        <p role="alert" className="form-error">
          {problem.resume ? `${problem.message} ${problem.resume}` : problem.message}
        </p>
      )}
    </form>
  );
}
