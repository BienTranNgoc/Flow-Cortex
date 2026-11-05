import {
  ApiError,
  AdminSupportRequestReplyStatus,
  type AdminSupportRequestDetail,
  type AdminSupportRequestDetailAllowedStatusesItem,
  type AdminSupportRequestDetailStatus,
  type SupportConversationMessage,
  type SupportRequestMessage,
} from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { usePageTitle } from '../../../app/usePageTitle';
import { Markdown } from '../Markdown';
import { supportCopy } from '../supportCopy';
import { supportProblemCode } from '../unwrapSupport';
import { ATTACHMENT_ACCEPT, UploadFailedError, addAttachments } from './attachments';
import './requests.css';
import { RequestTime } from './RequestTime';
import { RequesterLabel, StatusBadge } from './StatusBadge';
import {
  useReply,
  useRequestDetail,
  useSetStatus,
  useSubmitKey,
  useUploadedIds,
} from './useAdminRequests';

const copy = supportCopy.requests;
const REPLY_STATUSES = Object.values(AdminSupportRequestReplyStatus);
/** One broken image reads the request again at most this often, so an object that never loads cannot loop. */
const IMAGE_RETRY_MS = 60_000;
const MAX_REPLY_LENGTH = 5000;

interface Failure {
  message: string;
  reload: boolean;
}

export function RequestDetailPage() {
  const number = Number(useParams().number);
  const title = copy.detailTitle(number);
  usePageTitle(title);
  const detail = useRequestDetail(number);
  const lastImageRetry = useRef(0);

  const valid = Number.isSafeInteger(number) && number > 0;
  const notFound = !valid || supportProblemCode(detail.error) === 'request-not-found';

  // A failed background refetch keeps the request, and any reply being typed, on screen.
  let content;
  if (notFound) {
    content = (
      <p className="cortex-admin-error" role="alert">
        {copy.notFound}
      </p>
    );
  } else if (detail.error && !detail.data) {
    content = (
      <div className="cortex-admin-error">
        <p role="alert">{supportCopy.common.loadFailed}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => void detail.refetch()}>
          {supportCopy.common.retry}
        </button>
      </div>
    );
  } else if (!detail.data) {
    content = <output>{supportCopy.common.loading}</output>;
  } else {
    // Attachment URLs are presigned and expire, so a broken image asks for fresh ones.
    const onImageFailed = () => {
      if (Date.now() - lastImageRetry.current < IMAGE_RETRY_MS) return;
      lastImageRetry.current = Date.now();
      void detail.refetch();
    };
    content = (
      <>
        {detail.error && <p className="cortex-admin-stale">{supportCopy.common.refreshFailed}</p>}
        <RequestView
          request={detail.data}
          refresh={async () => (await detail.refetch()).data}
          onImageFailed={onImageFailed}
        />
      </>
    );
  }

  return (
    <main>
      <Link className="cortex-admin-back-link" to="/support/requests">
        {copy.backToInbox}
      </Link>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{copy.eyebrow}</p>
          <h1>{title}</h1>
        </div>
      </div>
      {content}
    </main>
  );
}

type Refresh = () => Promise<AdminSupportRequestDetail | undefined>;

function RequestView({
  request,
  refresh,
  onImageFailed,
}: Readonly<{
  request: AdminSupportRequestDetail;
  refresh: Refresh;
  onImageFailed: () => void;
}>) {
  const open = request.allowedStatuses.length > 0;
  return (
    <div className="request-detail">
      <section className="request-summary" aria-labelledby="request-subject">
        <h2 id="request-subject">{request.subject}</h2>
        <dl className="request-facts">
          <div>
            <dt>{copy.columns.status}</dt>
            <dd>
              <StatusBadge status={request.status} />
              {request.closedReason && (
                <span className="request-closed-reason">{copy.closedReason[request.closedReason]}</span>
              )}
            </dd>
          </div>
          <div>
            <dt>{copy.columns.type}</dt>
            <dd>{copy.type[request.type]}</dd>
          </div>
          <div>
            <dt>{copy.columns.requester}</dt>
            <dd>
              <RequesterLabel requester={request.requester} />
            </dd>
          </div>
          <div>
            <dt>{copy.createdAt}</dt>
            <dd>
              <RequestTime value={request.createdAt} />
            </dd>
          </div>
          <div>
            <dt>{copy.columns.activity}</dt>
            <dd>
              <RequestTime value={request.lastActivityAt} />
            </dd>
          </div>
        </dl>
        <StatusMenu request={request} refresh={refresh} />
      </section>

      <section className="request-section" aria-labelledby="request-thread">
        <h2 id="request-thread">{copy.thread}</h2>
        <ol className="request-thread">
          {request.messages.map((message) => (
            <Message key={message.id} message={message} onImageFailed={onImageFailed} />
          ))}
        </ol>
      </section>

      <Conversation request={request} />

      {open && (
        <ReplyForm
          key={request.number}
          request={request}
          refresh={refresh}
          blocked={request.requester.deleted}
        />
      )}
    </div>
  );
}

function Message({
  message,
  onImageFailed,
}: Readonly<{ message: SupportRequestMessage; onImageFailed: () => void }>) {
  return (
    <li className={`request-message request-message--${message.authorKind}`}>
      <p className="request-message-meta">
        <strong>{message.authorKind === 'member' ? copy.member : copy.admin}</strong>{' '}
        <RequestTime value={message.createdAt} />
      </p>
      <p className="request-message-body">{message.body}</p>
      {message.attachments.length > 0 && (
        <ul className="request-images" aria-label={copy.attachments}>
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

/** Not linked: nothing. Linked but deleted after 90 days: the expiry note. */
function Conversation({ request }: Readonly<{ request: AdminSupportRequestDetail }>) {
  if (!request.conversationExpired && !request.conversation?.length) return null;
  return (
    <section className="request-section" aria-labelledby="request-conversation">
      <h2 id="request-conversation">{copy.conversation}</h2>
      {request.conversationExpired ? (
        <p className="request-muted">{copy.conversationExpired}</p>
      ) : (
        <ol className="request-thread">
          {request.conversation?.map((message, index) => (
            <ConversationMessage key={`${message.createdAt}-${index}`} message={message} />
          ))}
        </ol>
      )}
    </section>
  );
}

function ConversationMessage({ message }: Readonly<{ message: SupportConversationMessage }>) {
  const outcome =
    message.outcome === 'no_answer' || message.outcome === 'blocked'
      ? copy.outcome[message.outcome]
      : undefined;
  return (
    <li className={`request-message request-message--${message.role === 'user' ? 'member' : 'assistant'}`}>
      <p className="request-message-meta">
        <strong>{message.role === 'user' ? copy.member : copy.assistant}</strong>{' '}
        <RequestTime value={message.createdAt} />
        {outcome && <span className="request-outcome">{outcome}</span>}
      </p>
      {message.role === 'assistant' ? (
        <div className="request-message-markdown">
          <Markdown>{message.text}</Markdown>
        </div>
      ) : (
        <p className="request-message-body">{message.text}</p>
      )}
    </li>
  );
}

function statusFailure(code: string | undefined): Failure {
  switch (code) {
    case 'request-state-changed':
      return { message: copy.stateChanged, reload: true };
    case 'request-closed':
      return { message: supportCopy.problems['request-closed'], reload: false };
    case 'request-not-found':
      return { message: copy.notFound, reload: false };
    default:
      return { message: copy.statusFailed, reload: false };
  }
}

/** Targets come only from the server's `allowedStatuses`; a refusal names the status the request now has. */
function StatusMenu({
  request,
  refresh,
}: Readonly<{ request: AdminSupportRequestDetail; refresh: Refresh }>) {
  const change = useSetStatus(request.number);
  const menuId = useId();
  const feedbackRef = useRef<HTMLDivElement>(null);
  const allowed = request.allowedStatuses;
  const [picked, setPicked] = useState<AdminSupportRequestDetailAllowedStatusesItem | ''>('');
  const target = picked && allowed.includes(picked) ? picked : '';
  const [failure, setFailure] = useState<Failure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const hadMenu = useRef(allowed.length > 0);

  // The focused control disappears when the request closes; focus moves to the outcome instead.
  useEffect(() => {
    if (hadMenu.current && allowed.length === 0 && feedbackRef.current) {
      const active = document.activeElement;
      if (!active || active === document.body) feedbackRef.current.focus();
    }
    hadMenu.current = allowed.length > 0;
  }, [allowed.length]);

  const label = (status: AdminSupportRequestDetailStatus) => copy.status[status];

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (change.isPending || !target) return;
    const from = request.status;
    setFailure(null);
    setAnnouncement('');
    change.mutate(target, {
      onSuccess: (result) => setAnnouncement(copy.statusChanged(label(result.status))),
      onError: async (error) => {
        const code = supportProblemCode(error);
        if (code === 'transition-not-allowed') {
          const current = await refresh();
          setFailure({
            message: copy.transitionNotAllowed(label(current?.status ?? from), label(target)),
            reload: false,
          });
          return;
        }
        if (code === 'request-closed') void refresh();
        setFailure(statusFailure(code));
      },
    });
  };

  return (
    <div className="request-status-menu">
      {allowed.length > 0 && (
        <form className="request-status-form" onSubmit={onSubmit}>
          <div className="cortex-admin-field">
            <label htmlFor={menuId}>{copy.changeStatus}</label>
            <select
              id={menuId}
              value={target}
              onChange={(event) => {
                setPicked(event.target.value as AdminSupportRequestDetailAllowedStatusesItem);
                setFailure(null);
              }}
            >
              <option value="" disabled>
                {copy.pickStatus}
              </option>
              {allowed.map((status) => (
                <option key={status} value={status}>
                  {label(status)}
                </option>
              ))}
            </select>
          </div>
          <button
            type="submit"
            className="cortex-admin-btn"
            aria-disabled={change.isPending || !target}
          >
            {change.isPending ? supportCopy.common.saving : copy.saveStatus}
          </button>
        </form>
      )}
      <div ref={feedbackRef} tabIndex={-1} className="request-feedback">
        {failure && (
          <div className="request-failure">
            <p role="alert">{failure.message}</p>
            {failure.reload && (
              <button
                type="button"
                className="cortex-admin-btn"
                onClick={() => {
                  setFailure(null);
                  void refresh();
                }}
              >
                {copy.reload}
              </button>
            )}
          </div>
        )}
        <p role="status" className="request-announcement">
          {announcement}
        </p>
      </div>
    </div>
  );
}

function replyFailure(error: unknown): Failure & { field?: string } {
  if (error instanceof UploadFailedError) return { message: copy.attachUploadFailed, reload: false };
  const code = supportProblemCode(error);
  switch (code) {
    case 'validation-failed': {
      const fields = error instanceof ApiError ? error.problem?.errors : undefined;
      return fields?.body
        ? { message: copy.replyFailed, reload: false, field: copy.replyLength }
        : { message: copy.replyFailed, reload: false };
    }
    case 'attachment-rejected':
      return { message: copy.attachRejected, reload: false };
    // The upload grant answers support-disabled while the Help Center switch is off; text replies still go through.
    case 'attachments-unavailable':
    case 'support-disabled':
      return { message: copy.attachUnavailable, reload: false };
    case 'request-closed':
    case 'requester-deleted':
      return { message: supportCopy.problems[code], reload: false };
    case 'request-state-changed':
      return { message: copy.stateChanged, reload: true };
    case 'idempotency-key-reused':
      return { message: copy.replyReused, reload: true };
    case 'request-not-found':
      return { message: copy.notFound, reload: false };
    default:
      return { message: copy.replyFailed, reload: false };
  }
}

function ReplyForm({
  request,
  refresh,
  blocked,
}: Readonly<{ request: AdminSupportRequestDetail; refresh: Refresh; blocked: boolean }>) {
  const reply = useReply(request.number);
  const submitKey = useSubmitKey();
  const uploaded = useUploadedIds();
  const formId = useId();
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const [body, setBody] = useState('');
  const [status, setStatus] = useState<AdminSupportRequestReplyStatus>('waiting_member');
  const [files, setFiles] = useState<File[]>([]);
  const [refused, setRefused] = useState<string[]>([]);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const bodyId = `${formId}-body`;
  const errorId = `${formId}-error`;
  const blockedId = `${formId}-blocked`;
  const fileId = `${formId}-files`;

  const edited = () => {
    submitKey.reset();
    setAnnouncement('');
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current || blocked) return;
    const text = body.trim();
    const length = [...text].length;
    if (length < 1 || length > MAX_REPLY_LENGTH) {
      setFieldError(copy.replyLength);
      inputRef.current?.focus();
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setFieldError(null);
    setFailure(null);
    setAnnouncement('');
    try {
      const uploadIds = await uploaded(files);
      const result = await reply.mutateAsync({
        body: { body: text, uploadIds, status },
        key: submitKey.current(),
      });
      submitKey.reset();
      setBody('');
      setFiles([]);
      setRefused([]);
      setAnnouncement(copy.replied(copy.status[result.status]));
    } catch (caught) {
      const next = replyFailure(caught);
      const code = supportProblemCode(caught);
      if (code === 'idempotency-key-reused') submitKey.reset();
      if (code === 'request-closed' || code === 'requester-deleted') void refresh();
      setFailure(next);
      if (next.field) {
        setFieldError(next.field);
        inputRef.current?.focus();
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <section className="request-section" aria-labelledby={`${formId}-heading`}>
      <h2 id={`${formId}-heading`}>{copy.reply}</h2>
      {blocked && (
        <p id={blockedId} className="request-muted">
          {copy.replyDisabled}
        </p>
      )}
      <form className="request-reply" noValidate onSubmit={onSubmit}>
        <fieldset disabled={blocked} className="request-reply-fields">
          <div className="cortex-admin-field request-reply-body">
            <label htmlFor={bodyId}>{copy.reply}</label>
            <textarea
              id={bodyId}
              ref={inputRef}
              rows={5}
              value={body}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={
                [fieldError ? errorId : undefined, blocked ? blockedId : undefined]
                  .filter(Boolean)
                  .join(' ') || undefined
              }
              readOnly={busy}
              onChange={(event) => {
                edited();
                setBody(event.target.value);
              }}
            />
            {fieldError && (
              <p id={errorId} role="alert">
                {fieldError}
              </p>
            )}
          </div>

          <fieldset className="request-reply-status">
            <legend>{copy.replyStatus}</legend>
            {REPLY_STATUSES.map((value) => (
              <label key={value}>
                <input
                  type="radio"
                  name={`${formId}-status`}
                  value={value}
                  checked={status === value}
                  aria-disabled={busy}
                  onChange={() => {
                    if (busyRef.current) return;
                    edited();
                    setStatus(value);
                  }}
                />
                {copy.status[value]}
              </label>
            ))}
          </fieldset>

          <div className="cortex-admin-field">
            <label htmlFor={fileId}>{copy.attachLabel}</label>
            <input
              id={fileId}
              type="file"
              multiple
              accept={ATTACHMENT_ACCEPT}
              onChange={(event) => {
                const picked = Array.from(event.target.files ?? []);
                event.target.value = '';
                if (busyRef.current) return;
                const next = addAttachments(files, picked);
                edited();
                setFiles(next.files);
                setRefused(next.refused);
              }}
            />
            {refused.length > 0 && (
              <ul className="request-refused" role="alert">
                {refused.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            )}
            {files.length > 0 && (
              <ul className="request-files">
                {files.map((file, index) => (
                  <li key={`${file.name}-${index}`}>
                    <span>{file.name}</span>
                    <button
                      type="button"
                      className="cortex-admin-btn cortex-admin-btn--sm"
                      aria-label={copy.attachRemove(file.name)}
                      aria-disabled={busy}
                      onClick={() => {
                        if (busyRef.current) return;
                        edited();
                        setFiles(files.filter((_, at) => at !== index));
                        setRefused([]);
                        document.getElementById(fileId)?.focus();
                      }}
                    >
                      {copy.attachRemoveShort}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </fieldset>

        {failure && (
          <div className="request-failure">
            <p role="alert">{failure.message}</p>
            {failure.reload && (
              <button
                type="button"
                className="cortex-admin-btn"
                onClick={() => {
                  setFailure(null);
                  void refresh();
                }}
              >
                {copy.reload}
              </button>
            )}
          </div>
        )}
        <div className="request-actions">
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" aria-disabled={busy || blocked}>
            {busy ? copy.sending : copy.send}
          </button>
          <p role="status" className="request-announcement">
            {announcement}
          </p>
        </div>
      </form>
    </section>
  );
}
