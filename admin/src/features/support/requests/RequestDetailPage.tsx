import {
  ApiError,
  AdminSupportRequestReplyStatus,
  type AdminSupportRequestDetail,
  type AdminSupportRequestDetailAllowedStatusesItem,
  type AdminSupportRequestDetailStatus,
  type SupportConversationMessage,
  type SupportRequestMessage,
} from '@flow-cortex/api-client';
import { javaStrip, Markdown } from '@flow-cortex/ui-kit';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { can } from '../../../app/permissions';
import { usePageTitle } from '../../../app/usePageTitle';
import { useMeQuery } from '../../auth/useAuth';
import { supportProblemCode } from '../unwrapSupport';
import '../markdown.css';
import { ATTACHMENT_ACCEPT, UploadFailedError, addAttachments } from './attachments';
import './requests.css';
import { RequestTime } from './RequestTime';
import { AssigneeLabel, RequesterLabel, StatusBadge } from './StatusBadge';
import { useAssignees } from './useAssignees';
import {
  useAssignRequest,
  useReply,
  useRequestDetail,
  useSetStatus,
  useSubmitKey,
  useUploadedIds,
} from './useAdminRequests';

const REPLY_STATUSES = Object.values(AdminSupportRequestReplyStatus);
/** One broken image reads the request again at most this often, so an object that never loads cannot loop. */
const IMAGE_RETRY_MS = 60_000;
const MAX_REPLY_LENGTH = 5000;

interface Failure {
  message: string;
  reload: boolean;
}

export function RequestDetailPage() {
  const { t } = useTranslation();
  const number = Number(useParams().number);
  const title = t('support.requests.detailTitle', { number });
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
        {t('support.requests.notFound')}
      </p>
    );
  } else if (detail.error && !detail.data) {
    content = (
      <div className="cortex-admin-error">
        <p role="alert">{t('support.common.loadFailed')}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => void detail.refetch()}>
          {t('support.common.retry')}
        </button>
      </div>
    );
  } else if (!detail.data) {
    content = <output>{t('support.common.loading')}</output>;
  } else {
    // Attachment URLs are presigned and expire, so a broken image asks for fresh ones.
    const onImageFailed = () => {
      if (Date.now() - lastImageRetry.current < IMAGE_RETRY_MS) return;
      lastImageRetry.current = Date.now();
      void detail.refetch();
    };
    content = (
      <>
        {detail.error && <p className="cortex-admin-stale">{t('support.common.refreshFailed')}</p>}
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
        {t('support.requests.backToInbox')}
      </Link>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.requests.eyebrow')}</p>
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
  const { t } = useTranslation();
  const open = request.allowedStatuses.length > 0;
  return (
    <div className="request-detail">
      <section className="request-summary" aria-labelledby="request-subject">
        <h2 id="request-subject">{request.subject}</h2>
        <dl className="request-facts">
          <div>
            <dt>{t('support.requests.columns.status')}</dt>
            <dd>
              <StatusBadge status={request.status} />
              {request.closedReason && (
                <span className="request-closed-reason">
                  {t(`support.requests.closedReason.${request.closedReason}`)}
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>{t('support.requests.columns.type')}</dt>
            <dd>{t(`support.requests.type.${request.type}`)}</dd>
          </div>
          <div>
            <dt>{t('support.requests.columns.requester')}</dt>
            <dd>
              <RequesterLabel requester={request.requester} />
            </dd>
          </div>
          <div className="request-fact-assignee">
            <dt>{t('support.requests.columns.assignee')}</dt>
            <dd>
              <AssigneeLabel assignee={request.assignee} />
              <AssigneePicker request={request} refresh={refresh} />
            </dd>
          </div>
          <div>
            <dt>{t('support.requests.createdAt')}</dt>
            <dd>
              <RequestTime value={request.createdAt} />
            </dd>
          </div>
          <div>
            <dt>{t('support.requests.columns.activity')}</dt>
            <dd>
              <RequestTime value={request.lastActivityAt} />
            </dd>
          </div>
        </dl>
        <StatusMenu request={request} refresh={refresh} />
      </section>

      <section className="request-section" aria-labelledby="request-thread">
        <h2 id="request-thread">{t('support.requests.thread')}</h2>
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
  const { t } = useTranslation();
  return (
    <li className={`request-message request-message--${message.authorKind}`}>
      <p className="request-message-meta">
        <strong>
          {message.authorKind === 'member'
            ? t('support.requests.member')
            : t('support.requests.admin')}
        </strong>{' '}
        <RequestTime value={message.createdAt} />
      </p>
      <p className="request-message-body">{message.body}</p>
      {message.attachments.length > 0 && (
        <ul className="request-images" aria-label={t('support.requests.attachments')}>
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

/** Not linked: nothing. Linked but deleted after 90 days: the expiry note. */
function Conversation({ request }: Readonly<{ request: AdminSupportRequestDetail }>) {
  const { t } = useTranslation();
  if (!request.conversationExpired && !request.conversation?.length) return null;
  return (
    <section className="request-section" aria-labelledby="request-conversation">
      <h2 id="request-conversation">{t('support.requests.conversation')}</h2>
      {request.conversationExpired ? (
        <p className="request-muted">{t('support.requests.conversationExpired')}</p>
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
  const { t } = useTranslation();
  const outcome =
    message.outcome === 'no_answer' || message.outcome === 'blocked'
      ? t(`support.requests.outcome.${message.outcome}`)
      : undefined;
  return (
    <li className={`request-message request-message--${message.role === 'user' ? 'member' : 'assistant'}`}>
      <p className="request-message-meta">
        <strong>
          {message.role === 'user' ? t('support.requests.member') : t('support.requests.assistant')}
        </strong>{' '}
        <RequestTime value={message.createdAt} />
        {outcome && <span className="request-outcome">{outcome}</span>}
      </p>
      {message.role === 'assistant' ? (
        <div className="request-message-markdown">
          <Markdown mode="answer" firstHeadingLevel={3}>
            {message.text}
          </Markdown>
        </div>
      ) : (
        <p className="request-message-body">{message.text}</p>
      )}
    </li>
  );
}

/** Eligible staff only, from the server's list; a refusal shows where it happened and a lost race reads the request again. */
function AssigneePicker({
  request,
  refresh,
}: Readonly<{ request: AdminSupportRequestDetail; refresh: Refresh }>) {
  const { t } = useTranslation();
  const editable = can(useMeQuery().data, 'support.ticket.assign') && request.status !== 'closed';
  const assignees = useAssignees(editable);
  const assign = useAssignRequest(request.number);
  const id = useId();
  const errorId = `${id}-error`;
  const current = request.assignee?.id ?? '';
  const [picked, setPicked] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const value = picked ?? current;
  const people = assignees.data ?? [];
  const holder = request.assignee;
  const holderListed = people.some((person) => person.id === current);

  if (!editable) return null;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (assign.isPending || value === current) return;
    setError(null);
    setAnnouncement('');
    assign.mutate(value || undefined, {
      onSuccess: (result) => {
        setPicked(null);
        setAnnouncement(
          result.assignee?.email
            ? t('support.requests.assigneeChanged', { email: result.assignee.email })
            : t('support.requests.assigneeCleared'),
        );
      },
      onError: (failure) => {
        const status = failure instanceof ApiError ? failure.status : undefined;
        const problem = failure instanceof ApiError ? failure.problem : undefined;
        if (status === 400) {
          setError(problem?.errors?.assigneeId ?? t('support.requests.assigneeFailed'));
        } else if (status === 409) {
          setError(problem?.detail ?? t('support.requests.stateChanged'));
          setPicked(null);
          void refresh();
        } else {
          setError(t('support.requests.assigneeFailed'));
        }
      },
    });
  };

  return (
    <form className="request-assignee-form" onSubmit={onSubmit}>
      <div className="cortex-admin-field">
        <label htmlFor={`${id}-select`}>{t('support.requests.assigneePicker')}</label>
        <select
          id={`${id}-select`}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => {
            setPicked(event.target.value);
            setError(null);
          }}
        >
          <option value="">{t('support.requests.unassigned')}</option>
          {current && !holderListed && (
            <option value={current}>
              {holder?.email && !holder.deleted ? holder.email : t('support.requests.assigneeDeleted')}
            </option>
          )}
          {people.map((person) => (
            <option key={person.id} value={person.id}>
              {person.email}
            </option>
          ))}
        </select>
      </div>
      <button type="submit" className="cortex-admin-btn" aria-disabled={assign.isPending || value === current}>
        {assign.isPending ? t('support.common.saving') : t('support.requests.saveAssignee')}
      </button>
      {assignees.isError && <p className="cortex-admin-form-error">{t('support.requests.assigneesFailed')}</p>}
      {error && (
        <p id={errorId} className="cortex-admin-form-error request-assignee-error" role="alert">
          {error}
        </p>
      )}
      <p role="status" className="request-announcement">
        {announcement}
      </p>
    </form>
  );
}

function statusFailure(code: string | undefined, t: TFunction): Failure {
  switch (code) {
    case 'request-state-changed':
      return { message: t('support.requests.stateChanged'), reload: true };
    case 'request-closed':
      return { message: t('support.problems.request-closed'), reload: false };
    case 'request-not-found':
      return { message: t('support.requests.notFound'), reload: false };
    default:
      return { message: t('support.requests.statusFailed'), reload: false };
  }
}

/** Targets come only from the server's `allowedStatuses`; a refusal names the status the request now has. */
function StatusMenu({
  request,
  refresh,
}: Readonly<{ request: AdminSupportRequestDetail; refresh: Refresh }>) {
  const { t } = useTranslation();
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

  const label = (status: AdminSupportRequestDetailStatus) =>
    t(`support.requests.status.${status}`);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (change.isPending || !target) return;
    const from = request.status;
    setFailure(null);
    setAnnouncement('');
    change.mutate(target, {
      onSuccess: (result) => {
        setPicked('');
        setAnnouncement(t('support.requests.statusChanged', { label: label(result.status) }));
      },
      onError: async (error) => {
        const code = supportProblemCode(error);
        if (code === 'transition-not-allowed') {
          const current = await refresh();
          setFailure({
            message: t('support.requests.transitionNotAllowed', {
              from: label(current?.status ?? from),
              to: label(target),
            }),
            reload: false,
          });
          return;
        }
        if (code === 'request-closed') void refresh();
        setFailure(statusFailure(code, t));
      },
    });
  };

  return (
    <div className="request-status-menu">
      {allowed.length > 0 && (
        <form className="request-status-form" onSubmit={onSubmit}>
          <div className="cortex-admin-field">
            <label htmlFor={menuId}>{t('support.requests.changeStatus')}</label>
            <select
              id={menuId}
              value={target}
              onChange={(event) => {
                setPicked(event.target.value as AdminSupportRequestDetailAllowedStatusesItem);
                setFailure(null);
              }}
            >
              <option value="" disabled>
                {t('support.requests.pickStatus')}
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
            {change.isPending ? t('support.common.saving') : t('support.requests.saveStatus')}
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
                {t('support.requests.reload')}
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

const REPLY_LENGTH_KEY = 'support.requests.replyLength';

function replyFailure(error: unknown, t: TFunction): Failure & { field?: string } {
  if (error instanceof UploadFailedError) {
    return { message: t('support.requests.attachUploadFailed'), reload: false };
  }
  const code = supportProblemCode(error);
  switch (code) {
    case 'validation-failed': {
      const fields = error instanceof ApiError ? error.problem?.errors : undefined;
      return fields?.body
        ? { message: t('support.requests.replyFailed'), reload: false, field: REPLY_LENGTH_KEY }
        : { message: t('support.requests.replyFailed'), reload: false };
    }
    case 'attachment-rejected':
      return { message: t('support.requests.attachRejected'), reload: false };
    // The upload grant answers support-disabled while the Help Center switch is off; text replies still go through.
    case 'attachments-unavailable':
    case 'support-disabled':
      return { message: t('support.requests.attachUnavailable'), reload: false };
    case 'request-closed':
    case 'requester-deleted':
      return { message: t(`support.problems.${code}`), reload: false };
    case 'request-state-changed':
      return { message: t('support.requests.stateChanged'), reload: true };
    case 'idempotency-key-reused':
      return { message: t('support.requests.replyReused'), reload: true };
    case 'request-not-found':
      return { message: t('support.requests.notFound'), reload: false };
    default:
      return { message: t('support.requests.replyFailed'), reload: false };
  }
}

function ReplyForm({
  request,
  refresh,
  blocked,
}: Readonly<{ request: AdminSupportRequestDetail; refresh: Refresh; blocked: boolean }>) {
  const { t } = useTranslation();
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
    const text = javaStrip(body);
    const length = [...text].length;
    if (length < 1 || length > MAX_REPLY_LENGTH) {
      setFieldError(REPLY_LENGTH_KEY);
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
      setAnnouncement(
        t('support.requests.replied', { label: t(`support.requests.status.${result.status}`) }),
      );
    } catch (caught) {
      const next = replyFailure(caught, t);
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
      <h2 id={`${formId}-heading`}>{t('support.requests.reply')}</h2>
      {blocked && (
        <p id={blockedId} className="request-muted">
          {t('support.requests.replyDisabled')}
        </p>
      )}
      <form className="request-reply" noValidate onSubmit={onSubmit}>
        <fieldset disabled={blocked} className="request-reply-fields">
          <div className="cortex-admin-field request-reply-body">
            <label htmlFor={bodyId}>{t('support.requests.reply')}</label>
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
                {t(fieldError)}
              </p>
            )}
          </div>

          <fieldset className="request-reply-status">
            <legend>{t('support.requests.replyStatus')}</legend>
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
                {t(`support.requests.status.${value}`)}
              </label>
            ))}
          </fieldset>

          <div className="cortex-admin-field">
            <label htmlFor={fileId}>{t('support.requests.attachLabel')}</label>
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
                  <li key={reason}>{t(reason)}</li>
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
                      aria-label={t('support.requests.attachRemove', { name: file.name })}
                      aria-disabled={busy}
                      onClick={() => {
                        if (busyRef.current) return;
                        edited();
                        setFiles(files.filter((_, at) => at !== index));
                        setRefused([]);
                        document.getElementById(fileId)?.focus();
                      }}
                    >
                      {t('support.requests.attachRemoveShort')}
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
                {t('support.requests.reload')}
              </button>
            )}
          </div>
        )}
        <div className="request-actions">
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" aria-disabled={busy || blocked}>
            {busy ? t('support.requests.sending') : t('support.requests.send')}
          </button>
          <p role="status" className="request-announcement">
            {announcement}
          </p>
        </div>
      </form>
    </section>
  );
}
