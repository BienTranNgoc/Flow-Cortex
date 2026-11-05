import { type SupportRequestDetail, type SupportRequestMessage } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react';
import { LoadFailed } from './GuidesTab';
import { AttachmentPicker, FailureNotice, SuspendedNotice } from './RequestForm';
import { supportCopy } from './supportCopy';
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

const copy = supportCopy.requests;
const timeFormat = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
/** One broken image reads the request again at most this often, so an object that never loads cannot loop. */
const IMAGE_RETRY_MS = 60_000;

export function StatusChip({ status }: Readonly<{ status: RequestStatus }>) {
  return <span className={`support-chip support-chip--${status}`}>{copy.statuses[status]}</span>;
}

function Message({
  message,
  onImageFailed,
}: Readonly<{ message: SupportRequestMessage; onImageFailed: () => void }>) {
  const fromMember = message.authorKind === 'member';
  return (
    <li className={`support-message support-message--${message.authorKind}`}>
      <p className="support-message__meta">
        <span className="support-message__author">{fromMember ? copy.you : copy.supportTeam}</span>{' '}
        <time dateTime={message.createdAt}>{timeFormat.format(new Date(message.createdAt))}</time>
      </p>
      <p className="support-message__body">{message.body}</p>
      {message.attachments.length > 0 && (
        <ul className="support-message__images">
          {message.attachments.map((attachment) => (
            <li key={attachment.position}>
              <a href={attachment.url} target="_blank" rel="noopener noreferrer">
                <img
                  src={attachment.url}
                  alt={copy.attachmentAlt(attachment.position)}
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
  const reply = useReply(number);
  const reload = useReloadRequest(number);
  const submitKey = useSubmitKey();
  const uploaded = useUploadedIds();
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
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
      setError(copy.replyLength);
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
      setError(failureOf(caught).fields.body ?? null);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <form className="support-request-form" noValidate onSubmit={onSubmit}>
      <div className="support-field">
        <label className="support-field__label" htmlFor={bodyId}>
          {copy.replyLabel}
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
            {error}
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
        {busy ? copy.sending : copy.replySend}
      </button>
    </form>
  );
}

function Actions({
  request,
  afterwards,
}: Readonly<{ request: SupportRequestDetail; afterwards: RefObject<HTMLElement> }>) {
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
          {copy.confirmResolved}
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
          {copy.cancel}
        </button>
      )}
      {allowed.cancel && askingCancel && (
        <div className="support-state" role="group" aria-label={copy.cancel}>
          <p>{copy.cancelQuestion}</p>
          <button
            type="button"
            className="support-button"
            aria-disabled={action.isPending || undefined}
            onClick={() => run('cancel')}
          >
            {copy.cancelYes}
          </button>
          <button
            ref={keepRef}
            type="button"
            className="support-button"
            onClick={() => setAskingCancel(false)}
          >
            {copy.cancelNo}
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
  const { dispatch } = useSupportPanel();
  return (
    <div className="support-state">
      <p>{copy.closedNotice}</p>
      {request.closedReason && <p>{copy.closedReasons[request.closedReason]}</p>}
      <button
        type="button"
        className="support-button support-button--primary"
        onClick={() => dispatch({ type: 'startRequest' })}
      >
        {copy.createAnother}
      </button>
    </div>
  );
}

/** One request: status, the thread with its images, and the actions its status allows. */
export function RequestThread({ number }: Readonly<{ number: number }>) {
  const detail = useRequestDetail(number);
  const lastImageRetry = useRef(0);
  const statusRef = useRef<HTMLParagraphElement>(null);

  if (detail.isPending) {
    return (
      <p className="support-state" role="status">
        {supportCopy.common.loading}
      </p>
    );
  }
  if (detail.isError) {
    if (isSuspended(detail.error)) return <SuspendedNotice />;
    if (supportProblemCode(detail.error) === 'request-not-found') {
      return (
        <p className="support-state" role="alert">
          {copy.notFound}
        </p>
      );
    }
    return <LoadFailed error={detail.error} onRetry={() => void detail.refetch()} />;
  }

  const request = detail.data;
  // Attachment URLs are presigned and expire, so a broken image asks for fresh ones.
  const onImageFailed = () => {
    if (Date.now() - lastImageRetry.current < IMAGE_RETRY_MS) return;
    lastImageRetry.current = Date.now();
    void detail.refetch();
  };

  return (
    <div className="support-request">
      <p ref={statusRef} className="support-request__meta" tabIndex={-1}>
        <StatusChip status={request.status} />
        <span>{copy.types[request.type]}</span>
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
