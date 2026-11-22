import type { FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams, useSearchParams } from 'react-router-dom';
import { AuthLink } from './AuthLink';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
import {
  emailLinkFailureFrom,
  useEmailLinkQuery,
  useUnsubscribeEmailLinkMutation,
  type EmailLinkFailure,
} from './useEmailLinks';

type Phase = 'loading' | 'invalid' | 'rateLimited' | 'error' | 'already' | 'done' | 'ready';

/**
 * Opened from the footer link of a mail. Showing the page only reads the link; the address is
 * unsubscribed when the member presses the button, never by opening it.
 */
export function EmailUnsubscribePage() {
  const { t } = useTranslation();
  const { token = '' } = useParams();
  const [searchParams] = useSearchParams();
  const view = useEmailLinkQuery(token);
  const unsubscribe = useUnsubscribeEmailLinkMutation(token, searchParams.get('src') ?? undefined);

  const viewFailure: EmailLinkFailure | null = view.isError
    ? emailLinkFailureFrom(view.error)
    : null;
  const pressFailure: EmailLinkFailure | null = unsubscribe.isError
    ? emailLinkFailureFrom(unsubscribe.error)
    : null;

  // A link the server calls dead stays dead whichever call said so, so the button goes away.
  let phase: Phase;
  if (token === '' || viewFailure === 'invalid' || pressFailure === 'invalid') {
    phase = 'invalid';
  } else if (unsubscribe.isSuccess) {
    phase = 'done';
  } else if (viewFailure) {
    phase = viewFailure;
  } else if (!view.isSuccess) {
    phase = 'loading';
  } else {
    phase = view.data.unsubscribed ? 'already' : 'ready';
  }

  useAuthPose(
    nodiPeekState({
      focused: null,
      pending: unsubscribe.isPending,
      failed: phase === 'invalid' || phase === 'rateLimited' || phase === 'error' || !!pressFailure,
      succeeded: phase === 'done',
    }),
  );

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    unsubscribe.mutate();
  };

  const shown = unsubscribe.data ?? view.data;
  const values = {
    list: shown ? t(`auth.emailLinks.list.${shown.list}`) : '',
    email: shown?.maskedEmail ?? '',
  };

  const message = phase === 'ready' ? pressFailure : phase;
  const failed = message === 'invalid' || message === 'rateLimited' || message === 'error';

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.emailLinks.title')}
      </h1>

      {phase === 'ready' && <p className="auth-subtitle">{t('auth.emailLinks.intro', values)}</p>}

      <div aria-live="polite">
        {message && (
          <p className={failed ? 'auth-alert' : 'auth-status'}>
            {t(`auth.emailLinks.${message}`, values)}
          </p>
        )}
      </div>

      {phase === 'ready' && (
        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('auth.emailLinks.formLabel')}
          className="auth-form"
        >
          <button
            type="submit"
            className="auth-btn auth-btn--primary"
            disabled={unsubscribe.isPending}
            aria-busy={unsubscribe.isPending}
          >
            {unsubscribe.isPending && <span className="auth-spinner" aria-hidden="true" />}
            {unsubscribe.isPending
              ? t('auth.emailLinks.submitting')
              : t(pressFailure ? 'auth.emailLinks.retry' : 'auth.emailLinks.submit')}
          </button>
        </form>
      )}

      {phase === 'error' && (
        <button
          type="button"
          className="auth-btn auth-btn--secondary"
          disabled={view.isFetching}
          aria-busy={view.isFetching}
          onClick={() => void view.refetch()}
        >
          {t('auth.emailLinks.retry')}
        </button>
      )}

      <p className="auth-footnote">
        <AuthLink to="/login">{t('auth.emailLinks.backToSignIn')}</AuthLink>
      </p>
    </>
  );
}
