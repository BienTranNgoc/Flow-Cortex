import type { TFunction } from 'i18next';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { RefundRequestDialog } from './RefundRequestDialog';
import { refundBlockState, type RefusalSentence } from './refundBlockState';
import {
  isNotCancellable,
  isNotEligible,
  reasonFieldError,
  useCancelRefundRequest,
  useRefundEligibility,
  useRequestRefund,
} from './useRefund';

const POLICY_PATH = '/legal/termination-refund';
const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium' };
const TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

type CancelOutcome = 'none' | 'refused' | 'failed';

/**
 * The change-of-mind refund block of a payment. What it shows is decided by {@link refundBlockState}; this
 * only renders it. While the eligibility is loading or failed to load it renders nothing, so a refund
 * outage never hides the payment itself.
 */
export function RefundBlock({ transactionId }: Readonly<{ transactionId: string }>) {
  const { t } = useTranslation();
  const titleId = useId();
  const eligibility = useRefundEligibility(transactionId);
  const state = refundBlockState(eligibility.isError ? undefined : eligibility.data);
  if (state.shape === 'hidden') return null;

  return (
    <section className="refund-block" aria-labelledby={titleId}>
      <h2 id={titleId} className="refund-block__title">
        {t('billing.refund.title')}
      </h2>
      {state.shape === 'invite' && (
        <Invite transactionId={transactionId} daysRemaining={state.daysRemaining} />
      )}
      {state.shape === 'processing' && (
        <Processing
          transactionId={transactionId}
          code={state.code}
          requestedAt={state.requestedAt}
          cancellable={state.cancellable}
        />
      )}
      {state.shape === 'rejected' && (
        <>
          <p className="refund-block__text">
            {t('billing.refund.rejected.line', { code: state.code })}
          </p>
          {state.reason && (
            <p className="refund-block__note">
              {t('billing.refund.rejected.reason', { reason: state.reason })}
            </p>
          )}
        </>
      )}
      {state.shape === 'closed' && (
        <p className="refund-block__text">{sentenceText(state.sentence, t)}</p>
      )}
      <Link
        to={POLICY_PATH}
        target="_blank"
        rel="noreferrer"
        className="refund-block__policy action-link"
      >
        {t('billing.refund.policy')}
        <span className="visually-hidden"> {t('billing.refund.policyNewTab')}</span>
      </Link>
    </section>
  );
}

function sentenceText(sentence: RefusalSentence, t: TFunction): string {
  const { nextTurnAt, ...rest } = sentence.values;
  const date = nextTurnAt ? formatDateTime(nextTurnAt, activeLocale(), DATE_FORMAT) : undefined;
  return t(sentence.key, { ...rest, ...(date ? { date } : {}) });
}

function Invite({
  transactionId,
  daysRemaining,
}: Readonly<{ transactionId: string; daysRemaining: number }>) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const request = useRequestRefund(transactionId);
  const failed =
    request.isError && !isNotEligible(request.error) && !reasonFieldError(request.error);

  const close = () => {
    setOpen(false);
    request.reset();
  };

  const send = (reason: string) => {
    request.mutate(reason, {
      onSuccess: close,
      onError: (error) => {
        if (isNotEligible(error)) close();
      },
    });
  };

  return (
    <>
      <p className="refund-block__text">
        {daysRemaining <= 1
          ? t('billing.refund.invite.today')
          : t('billing.refund.invite.days', { days: daysRemaining })}
      </p>
      <div className="refund-block__actions">
        <button type="button" className="refund-primary" onClick={() => setOpen(true)}>
          {t('billing.refund.request')}
        </button>
      </div>
      <RefundRequestDialog
        open={open}
        sending={request.isPending}
        failed={failed}
        fieldError={request.isError ? reasonFieldError(request.error) : undefined}
        onClose={close}
        onSend={send}
      />
    </>
  );
}

function Processing({
  transactionId,
  code,
  requestedAt,
  cancellable,
}: Readonly<{ transactionId: string; code: string; requestedAt: string; cancellable: boolean }>) {
  const { t } = useTranslation();
  const [outcome, setOutcome] = useState<CancelOutcome>('none');
  const cancel = useCancelRefundRequest(transactionId);

  const send = () => {
    setOutcome('none');
    cancel.mutate(undefined, {
      onError: (error) => setOutcome(isNotCancellable(error) ? 'refused' : 'failed'),
    });
  };

  return (
    <>
      <p className="refund-block__text">{t('billing.refund.processing.line', { code })}</p>
      <p className="refund-block__note">
        {t('billing.refund.processing.sent')}{' '}
        <time dateTime={requestedAt}>
          {formatDateTime(requestedAt, activeLocale(), TIME_FORMAT)}
        </time>
      </p>
      {outcome !== 'none' && (
        <p className="refund-block__notice" role="status">
          {outcome === 'refused'
            ? t('billing.refund.processing.cancelRefused')
            : t('billing.refund.processing.cancelFailed')}
        </p>
      )}
      {cancellable && (
        <div className="refund-block__actions">
          <button
            type="button"
            aria-disabled={cancel.isPending}
            aria-busy={cancel.isPending}
            onClick={() => {
              if (!cancel.isPending) send();
            }}
          >
            {cancel.isPending
              ? t('billing.refund.processing.cancelling')
              : t('billing.refund.processing.cancel')}
          </button>
        </div>
      )}
    </>
  );
}
