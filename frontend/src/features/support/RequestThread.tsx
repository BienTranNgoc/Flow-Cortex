import { type SupportRequestDetail, type SupportRequestMessage } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { LoadFailed, RefreshFailed } from './GuidesTab';
import { AttachmentPicker, FailureNotice, SuspendedNotice } from './RequestForm';
import { useSupportPanel } from './supportPanelState';
import {
  ALLOWED_ACTIONS,
  failureOf,
  isSuspended,
  textLength,
  useReloadRequest,
  useReply,
  useRequestAction,
  useRequestDetail,
  useSubmitKey,
  useUploadedIds,
  type RequestStatus,
} from './useRequests';
import { supportProblemCode } from './useSupportKb';

const MESSAGE_TIME: Intl.DateTimeFormatOptions = {
  dateStyle: 'short',
  timeStyle: 'short',
  hourCycle: 'h23',
  timeZone: 'Asia/Ho_Chi_Minh',
};
/** One broken image reads the request again at most this often, so an object that never loads cannot loop. */
const IMAGE_RETRY_MS = 60_000;

export function StatusChip({ status }: Readonly<{ status: RequestStatus }>) {
  const { t } = useTranslation();
  return (
    <span className={`support-chip support-chip--${status}`}>
      {t(`support.requests.statuses.${status}`)}
    </span>
  );
}

function Message({
  message,
  onImageFailed,
}: Readonly<{ message: SupportRequestMessage; onImageFailed: () => void }>) {
  const { t } = useTranslation();
  const fromMember = message.authorKind === 'member';
  return (
    <li className={`support-message support-message--${message.authorKind}`}>
      <p className="support-message__meta">
        <span className="support-message__author">
          {fromMember ? t('support.requests.you') : t('support.requests.supportTeam')}
        </span>{' '}
        <time dateTime={message.createdAt}>
          {formatDateTime(message.createdAt, activeLocale(), MESSAGE_TIME)}
        </time>
      </p>
      <p className="support-message__body">{message.body}</p>
      {message.attachments.length > 0 && (
        <ul className="support-message__images">
          {message.attachments.map((attachment) => (
            <li key={attachment.position}>
              <a href={attachment.url} target="_blank" rel="noopener noreferrer">
                <img
                  src={attachment.url}
                  alt={t('support.requests.attachmentAlt', { position: attachment.position })}
                  loading="lazy"
                  onError={onImageFailed}
                />
              </a>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

function ReplyForm({ number }: Readonly<{ number: number }>) {
  const { t } = useTranslation();
  const reply = useReply(number);
  const reload = useReloadRequest(number);
  const submitKey = useSubmitKey();
  const uploaded = useUploadedIds();
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  // A client check keeps its key so a language switch re-renders it; a server message stays as sent.
  const [error, setError] = useState<{ key: string } | { text: string } | null>(null);
  const [failed, setFailed] = useState<{ error: unknown } | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const bodyId = useId();

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    const length = textLength(body);
    if (length < 1 || length > 5000) {
      setError({ key: 'support.requests.replyLength' });
      inputRef.current?.focus();
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError(null);
    setFailed(null);
    try {
      const uploadIds = await uploaded(files);
      await reply.mutateAsync({ body: { body: body.trim(), uploadIds }, key: submitKey.current() });
      submitKey.reset();
      setBody('');
      setFiles([]);
      inputRef.current?.focus();
    } catch (caught) {
      setFailed({ error: caught });
      const text = failureOf(caught, t).fields.body;
      setError(text ? { text } : null);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <form className="support-request-form" noValidate onSubmit={onSubmit}>
      <div className="support-field">
        <label className="support-field__label" htmlFor={bodyId}>
          {t('support.requests.replyLabel')}
        </label>
        <textarea
          id={bodyId}
          ref={inputRef}
          className="support-field__input support-field__input--multiline"
          rows={3}
          value={body}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${bodyId}-error` : undefined}
          readOnly={busy}
          onChange={(event) => {
            submitKey.reset();
            setBody(event.target.value);
          }}
        />
        {error && (
          <p id={`${bodyId}-error`} className="support-field__error">
            {'key' in error ? t(error.key) : error.text}
          </p>
        )}
      </div>
      <AttachmentPicker
        files={files}
        disabled={busy}
        onChange={(next) => {
          submitKey.reset();
          setFiles(next);
        }}
      />
      {failed && (
        <FailureNotice
          error={failed.error}
          onReload={() => {
            setFailed(null);
            void reload();
          }}
        />
      )}
      <button
        type="submit"
        className="support-button support-button--primary"
        aria-disabled={busy || undefined}
      >
        {busy ? t('support.requests.sending') : t('support.requests.replySend')}
      </button>
    </form>
  );
}

function Actions({
  request,
  afterwards,
}: Readonly<{ request: SupportRequestDetail; afterwards: RefObject<HTMLElement> }>) {
  const { t } = useTranslation();
  const action = useRequestAction(request.number);
  const reload = useReloadRequest(request.number);
  const [askingCancel, setAskingCancel] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const keepRef = useRef<HTMLButtonElement>(null);
  const asked = useRef(false);
  const allowed = ALLOWED_ACTIONS[request.status];

  useEffect(() => {
    if (askingCancel) keepRef.current?.focus();
    else if (asked.current) cancelRef.current?.focus();
    asked.current = askingCancel;
  }, [askingCancel]);

  if (!allowed.cancel && !allowed.confirm) return null;
  // The pressed button leaves with the old status, so focus moves to the request's status.
  const run = (kind: 'confirm' | 'cancel') => {
    if (action.isPending) return;
    action.mutate(kind, { onSuccess: () => afterwards.current?.focus() });
  };
  return (
    <div className="support-request__actions">
      {allowed.confirm && (
        <button
          type="button"
          className="support-button support-button--primary"
          aria-disabled={action.isPending || undefined}
          onClick={() => run('confirm')}
        >
          {t('support.requests.confirmResolved')}
        </button>
      )}
      {allowed.cancel && !askingCancel && (
        <button
          ref={cancelRef}
          type="button"
          className="support-button"
          aria-disabled={action.isPending || undefined}
          onClick={() => setAskingCancel(true)}
        >
          {t('support.requests.cancel')}
        </button>
      )}
      {allowed.cancel && askingCancel && (
        <div className="support-state" role="group" aria-label={t('support.requests.cancel')}>
          <p>{t('support.requests.cancelQuestion')}</p>
          <button
            type="button"
            className="support-button"
            aria-disabled={action.isPending || undefined}
            onClick={() => run('cancel')}
          >
            {t('support.requests.cancelYes')}
          </button>
          <button
            ref={keepRef}
            type="button"
            className="support-button"
            onClick={() => setAskingCancel(false)}
          >
            {t('support.requests.cancelNo')}
          </button>
        </div>
      )}
      {action.isError && (
        <FailureNotice
          error={action.error}
          onReload={() => {
            action.reset();
            void reload();
          }}
        />
      )}
    </div>
  );
}

function ClosedNotice({ request }: Readonly<{ request: SupportRequestDetail }>) {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  return (
    <div className="support-state">
      <p>{t('support.requests.closedNotice')}</p>
      {request.closedReason && <p>{t(`support.requests.closedReasons.${request.closedReason}`)}</p>}
      <button
        type="button"
        className="support-button support-button--primary"
        onClick={() => dispatch({ type: 'startRequest' })}
      >
        {t('support.requests.createAnother')}
      </button>
    </div>
  );
}

/** One request: status, the thread with its images, and the actions its status allows. */
export function RequestThread({ number }: Readonly<{ number: number }>) {
  const { t } = useTranslation();
  const detail = useRequestDetail(number);
  const lastImageRetry = useRef(0);
  const statusRef = useRef<HTMLParagraphElement>(null);

  if (detail.isPending) {
    return (
      <p className="support-state" role="status">
        {t('support.common.loading')}
      </p>
    );
  }
  // A failed background read keeps the thread, and the reply being typed, on screen; only an
  // answer that says the request is out of reach replaces it.
  const request = detail.data;
  const outOfReach =
    detail.isError &&
    (isSuspended(detail.error) || supportProblemCode(detail.error) === 'request-not-found');
  if (detail.isError && (request === undefined || outOfReach)) {
    if (isSuspended(detail.error)) return <SuspendedNotice />;
    if (supportProblemCode(detail.error) === 'request-not-found') {
      return (
        <p className="support-state" role="alert">
          {t('support.requests.notFound')}
        </p>
      );
    }
    return <LoadFailed error={detail.error} onRetry={() => void detail.refetch()} />;
  }
  if (request === undefined) return null;

  // Attachment URLs are presigned and expire, so a broken image asks for fresh ones.
  const onImageFailed = () => {
    if (Date.now() - lastImageRetry.current < IMAGE_RETRY_MS) return;
    lastImageRetry.current = Date.now();
    void detail.refetch();
  };

  return (
    <div className="support-request">
      {detail.isError && <RefreshFailed onRetry={() => void detail.refetch()} />}
      <p ref={statusRef} className="support-request__meta" tabIndex={-1}>
        <StatusChip status={request.status} />
        <span>{t(`support.requests.types.${request.type}`)}</span>
      </p>
      <p className="support-request__subject">{request.subject}</p>
      <ol className="support-thread">
        {request.messages.map((message) => (
          <Message key={message.id} message={message} onImageFailed={onImageFailed} />
        ))}
      </ol>
      {ALLOWED_ACTIONS[request.status].reply ? (
        <ReplyForm key={request.number} number={request.number} />
      ) : (
        <ClosedNotice request={request} />
      )}
      <Actions request={request} afterwards={statusRef} />
    </div>
  );
}
