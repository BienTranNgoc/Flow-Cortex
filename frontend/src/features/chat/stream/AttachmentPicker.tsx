import { useId, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { AttachmentItem, AttachmentProblem, Attachments } from './useAttachments';

const ACCEPT = '.jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp';

interface AttachmentPickerProps {
  attachments: Attachments;
  max: number;
  /** The visible label of the file button. */
  label: string;
  disabled?: boolean;
}

function problemText(problem: AttachmentProblem | undefined, t: ReturnType<typeof useTranslation>['t']) {
  if (problem?.kind === 'key') return t(problem.messageKey, problem.values ?? {});
  if (problem?.kind === 'text') return problem.message;
  return t('chat.attach.failed');
}

function ItemLine({ item, onRemove }: Readonly<{ item: AttachmentItem; onRemove: () => void }>) {
  const { t } = useTranslation();
  return (
    <li className="chat-attachment">
      <span className="chat-attachment__name">{item.fileName}</span>
      {item.status === 'uploading' && <span role="status">{t('chat.attach.uploading')}</span>}
      {item.status === 'ready' && <span className="chat-attachment__ok">{t('chat.attach.ready')}</span>}
      {item.status === 'problem' && (
        <span role="alert" className="chat-attachment__problem">
          {problemText(item.problem, t)}
        </span>
      )}
      <button type="button" className="chat-button chat-button--quiet" onClick={onRemove}>
        {t('chat.attach.remove', { name: item.fileName })}
      </button>
    </li>
  );
}

/** Picks images and lists them with their upload state; the uploads themselves live in `attachments`. */
export function AttachmentPicker({ attachments, max, label, disabled }: Readonly<AttachmentPickerProps>) {
  const { t } = useTranslation();
  const inputId = useId();

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    attachments.add(Array.from(event.target.files ?? []));
    event.target.value = '';
  };

  return (
    <div className="chat-attach">
      <label className="chat-button chat-attach__button" htmlFor={inputId}>
        {label}
      </label>
      <input
        id={inputId}
        className="chat-sr-only"
        type="file"
        accept={ACCEPT}
        multiple={max > 1}
        disabled={disabled}
        onChange={onPick}
      />
      {attachments.overflow && (
        <p role="alert" className="chat-attachment__problem">
          {t('chat.attach.tooMany', { max })}
        </p>
      )}
      {attachments.items.length > 0 && (
        <ul className="chat-attachments">
          {attachments.items.map((item) => (
            <ItemLine key={item.localId} item={item} onRemove={() => attachments.remove(item.localId)} />
          ))}
        </ul>
      )}
    </div>
  );
}
