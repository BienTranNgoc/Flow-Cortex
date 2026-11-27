import type { AuditLogItem } from '@flow-cortex/api-client';
import { X } from '@phosphor-icons/react';
import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { actorName } from './auditActor';

interface AuditDetailPanelProps {
  readonly item: AuditLogItem;
  readonly onClose: () => void;
  readonly onFilterActor: (item: AuditLogItem) => void;
}

function Field({ label, children }: Readonly<{ label: string; children: ReactNode }>) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/** Every recorded field of one audit row, beside the table (full width below 1024px). */
export function AuditDetailPanel({
  item,
  onClose,
  onFilterActor,
}: Readonly<AuditDetailPanelProps>) {
  const { t } = useTranslation();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const none = t('audit.detail.none');
  const summary =
    item.changeSummary === null || item.changeSummary === undefined
      ? undefined
      : JSON.stringify(item.changeSummary, null, 2);
  const failed = item.status === 'failed';

  useEffect(() => {
    headingRef.current?.focus();
  }, [item.id]);

  const closeOnEscape = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
    }
  };

  return (
    <aside
      className="audit-detail cortex-admin-panel"
      aria-labelledby="audit-detail-title"
      onKeyDown={closeOnEscape}
    >
      <header className="cortex-admin-section-heading">
        <h2 id="audit-detail-title" ref={headingRef} tabIndex={-1}>
          {t('audit.detail.title')}
        </h2>
        <button
          type="button"
          className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm audit-detail__close"
          onClick={onClose}
          aria-label={t('audit.detail.close')}
        >
          <X size={16} aria-hidden="true" />
        </button>
      </header>
      <dl className="cortex-admin-meta-list audit-detail__fields">
        <Field label={t('audit.detail.time')}>
          <Timestamp value={item.occurredAt} />
        </Field>
        <Field label={t('audit.detail.actor')}>
          <span>{actorName(item) ?? none}</span>
          {item.actorName && item.actorEmail && (
            <span className="audit-detail__sub">{item.actorEmail}</span>
          )}
          {item.actorUserId && (
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm audit-detail__action"
              onClick={() => onFilterActor(item)}
            >
              {t('audit.detail.filterByActor')}
            </button>
          )}
        </Field>
        <Field label={t('audit.detail.actorId')}>{item.actorUserId ?? none}</Field>
        <Field label={t('audit.detail.action')}>
          <code>{item.action ?? none}</code>
        </Field>
        <Field label={t('audit.detail.resourceType')}>{item.resourceType ?? none}</Field>
        <Field label={t('audit.detail.resourceId')}>{item.resourceId ?? none}</Field>
        <Field label={t('audit.detail.resourceLabel')}>{item.resourceLabel ?? none}</Field>
        <Field label={t('audit.detail.status')}>
          {item.status ? (
            <StatusPill
              tone={failed ? 'error' : 'success'}
              label={t(failed ? 'audit.status.failed' : 'audit.status.success')}
            />
          ) : (
            none
          )}
        </Field>
        <Field label={t('audit.detail.errorCode')}>{item.errorCode ?? none}</Field>
        <Field label={t('audit.detail.ip')}>{item.ip ?? none}</Field>
        <Field label={t('audit.detail.userAgent')}>{item.userAgent ?? none}</Field>
        <Field label={t('audit.detail.origin')}>{item.origin ?? none}</Field>
        <Field label={t('audit.detail.changeSummary')}>
          {summary === undefined ? none : <pre className="audit-detail__json">{summary}</pre>}
        </Field>
        <Field label={t('audit.detail.id')}>{item.id ?? none}</Field>
      </dl>
    </aside>
  );
}
