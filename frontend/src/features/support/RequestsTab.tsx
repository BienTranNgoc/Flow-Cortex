import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { LoadFailed } from './GuidesTab';
import { RequestForm, SuspendedNotice } from './RequestForm';
import { RequestThread, StatusChip } from './RequestThread';
import { useSupportPanel } from './supportPanelState';
import { isSuspended, useRequestList } from './useRequests';
import './requests.css';

function RequestList() {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const list = useRequestList();

  if (list.isPending) {
    return (
      <p className="support-state" role="status">
        {t('support.common.loading')}
      </p>
    );
  }
  if (list.isError) {
    if (isSuspended(list.error)) return <SuspendedNotice />;
    return <LoadFailed error={list.error} onRetry={() => void list.refetch()} />;
  }

  return (
    <>
      <button
        type="button"
        className="support-button support-button--primary"
        onClick={() => dispatch({ type: 'startRequest' })}
      >
        {t('support.requests.create')}
      </button>
      {list.data.length === 0 ? (
        <p className="support-state">{t('support.requests.empty')}</p>
      ) : (
        <ul className="support-list">
          {list.data.map((request) => (
            <li key={request.number}>
              <button
                type="button"
                className="support-list__item"
                onClick={() => dispatch({ type: 'selectRequest', request: request.number })}
              >
                <span className="support-request__row">
                  <span className="support-request__row-subject">{request.subject}</span>
                  {request.memberUnread && (
                    <span className="support-unread-dot">
                      <span className="support-visually-hidden">{t('support.requests.unread')}</span>
                    </span>
                  )}
                </span>
                <span className="support-request__row">
                  <span className="support-list__meta">#{request.number}</span>
                  <StatusChip status={request.status} />
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/** Requests: the member's list, one request's thread, or the new-request form. */
export function RequestsTab() {
  const { t } = useTranslation();
  const { state, dispatch } = useSupportPanel();
  const { request, newRequest } = state;
  const view = request !== null ? `request-${request}` : newRequest ? 'new' : 'list';
  const headingRef = useRef<HTMLHeadingElement>(null);
  const shownView = useRef(view);

  // Only a move inside the tab takes focus: opening the tab leaves it on the tab itself.
  useEffect(() => {
    if (shownView.current === view) return;
    shownView.current = view;
    headingRef.current?.focus();
  }, [view]);

  const title =
    request !== null
      ? t('support.requests.selected', { number: request })
      : newRequest
        ? t('support.requests.newRequest')
        : t('support.requests.listHeading');

  return (
    <div className="support-requests">
      {view !== 'list' && (
        <button
          type="button"
          className="support-back"
          onClick={() => dispatch({ type: 'selectRequest', request: null })}
        >
          {t('support.requests.backToList')}
        </button>
      )}
      <h3 ref={headingRef} className="support-requests__title" tabIndex={-1}>
        {title}
      </h3>
      {request !== null ? (
        <RequestThread key={request} number={request} />
      ) : newRequest ? (
        <RequestForm prefill={state.prefill} />
      ) : (
        <RequestList />
      )}
    </div>
  );
}
