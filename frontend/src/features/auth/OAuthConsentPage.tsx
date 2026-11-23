import { ApiError, StepUpRequestPurpose } from '@flow-cortex/api-client';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { openInCurrentTab } from '../workflow/browser';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
import {
  authorizeParams,
  CONSENT_PATH,
  consentRedirect,
  consentView,
  grantFailure,
  rememberConsentQuery,
  restoreConsentQuery,
  tabStorage,
  type ConsentCheckInput,
  type GrantFailure,
} from './oauthConsent';
import { RequireAuth } from './RequireAuth';
import { StepUpDialog } from './StepUpDialog';
import { useAuthToken, useMeQuery, useSessionReady } from './useAuth';
import { useGrantOAuthMutation, useOAuthCheckQuery } from './useOAuthConsent';
import './OAuthConsentPage.css';

function hostOf(uri: string): string {
  try {
    return new URL(uri).host;
  } catch {
    return uri;
  }
}

function checkInput(query: ReturnType<typeof useOAuthCheckQuery>): ConsentCheckInput {
  if (query.data) return { phase: 'answered', check: query.data };
  if (query.isError) {
    return {
      phase: 'failed',
      status: query.error instanceof ApiError ? query.error.status : undefined,
    };
  }
  return { phase: 'pending' };
}

function OAuthConsentPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const params = useMemo(() => authorizeParams(searchParams), [searchParams]);
  const check = useOAuthCheckQuery(params);
  const me = useMeQuery();
  const grant = useGrantOAuthMutation();

  const [askingPassword, setAskingPassword] = useState(false);
  const [failure, setFailure] = useState<GrantFailure | null>(null);
  const [leaving, setLeaving] = useState(false);

  const view = consentView(checkInput(check));
  const answered = check.data;
  const host = answered ? hostOf(answered.redirectUri) : '';

  // A fault of the request itself goes to the client as the OAuth error it is, but only on the
  // visitor's click: anyone can register a redirect URI, so following it unasked is an open redirect.
  const sendBack = () => {
    if (!answered?.error || leaving) return;
    setLeaving(true);
    openInCurrentTab(
      consentRedirect(answered.redirectUri, { error: answered.error, state: params.state }),
    );
  };

  useAuthPose(
    nodiPeekState({
      focused: null,
      pending: grant.isPending || leaving,
      failed:
        view === 'invalidLink' ||
        view === 'unavailable' ||
        view === 'failed' ||
        view === 'notEligible' ||
        failure !== null,
      succeeded: false,
    }),
  );

  const deny = () => {
    if (!answered || leaving) return;
    setLeaving(true);
    openInCurrentTab(
      consentRedirect(answered.redirectUri, { error: 'access_denied', state: params.state }),
    );
  };

  const onProof = (stepUpProof: string) => {
    setAskingPassword(false);
    setFailure(null);
    grant.mutate(
      { ...params, stepUpProof },
      {
        onSuccess: ({ redirectTo }) => {
          setLeaving(true);
          openInCurrentTab(redirectTo);
        },
        onError: (error) => {
          const refused =
            error instanceof ApiError ? grantFailure(error.status, error.problem?.code) : 'failed';
          setFailure(refused);
          if (refused === 'stepUp') setAskingPassword(true);
        },
      },
    );
  };

  const busy = grant.isPending || leaving;

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.oauthConsent.title')}
      </h1>

      <div aria-live="polite">
        {view === 'loading' && (
          <p role="status" className="auth-status">
            {t('auth.oauthConsent.loading')}
          </p>
        )}

        {view === 'invalidLink' && (
          <p role="alert" className="auth-alert">
            {t('auth.oauthConsent.invalidLink')}
          </p>
        )}

        {view === 'unavailable' && (
          <p role="alert" className="auth-alert">
            {t('auth.oauthConsent.unavailable')}
          </p>
        )}

        {view === 'failed' && (
          <>
            <p role="alert" className="auth-alert">
              {t('auth.oauthConsent.failed')}
            </p>
            <button
              type="button"
              className="auth-btn auth-btn--secondary"
              disabled={check.isFetching}
              aria-busy={check.isFetching}
              onClick={() => void check.refetch()}
            >
              {t('auth.oauthConsent.retry')}
            </button>
          </>
        )}

        {view === 'redirectError' && (
          <>
            <p role="alert" className="auth-alert">
              {t('auth.oauthConsent.redirectError', { host })}
            </p>
            <button
              type="button"
              className="auth-btn auth-btn--secondary"
              disabled={leaving}
              aria-busy={leaving}
              onClick={sendBack}
            >
              {t('auth.oauthConsent.returnToClient', { host })}
            </button>
          </>
        )}
      </div>

      {answered && (view === 'ready' || view === 'notEligible') && (
        <>
          <p className="auth-subtitle oauth-consent__intro">{t('auth.oauthConsent.intro')}</p>

          <dl className="oauth-consent__facts">
            <div className="oauth-consent__fact">
              <dt>{t('auth.oauthConsent.clientLabel')}</dt>
              <dd>{answered.clientName}</dd>
            </div>
            <div className="oauth-consent__fact">
              <dt>{t('auth.oauthConsent.hostLabel')}</dt>
              <dd>{host}</dd>
            </div>
            {me.data?.email && (
              <div className="oauth-consent__fact">
                <dt>{t('auth.oauthConsent.accountLabel')}</dt>
                <dd>{me.data.email}</dd>
              </div>
            )}
          </dl>
          <p className="oauth-consent__note">{t('auth.oauthConsent.nameHint')}</p>

          {view === 'ready' ? (
            <p className="oauth-consent__note">{t('auth.oauthConsent.permission')}</p>
          ) : (
            <p role="alert" className="auth-alert">
              {t('auth.oauthConsent.notEligible')}
            </p>
          )}

          {failure && (
            <p role="alert" className="auth-alert">
              {t(`auth.oauthConsent.grantErrors.${failure}`)}
            </p>
          )}

          <div className="oauth-consent__actions">
            <button
              type="button"
              className="auth-btn auth-btn--secondary"
              disabled={busy}
              onClick={deny}
            >
              {t('auth.oauthConsent.deny')}
            </button>
            {view === 'ready' && (
              <button
                type="button"
                className="auth-btn auth-btn--primary"
                disabled={busy}
                aria-busy={busy}
                onClick={() => {
                  setFailure(null);
                  setAskingPassword(true);
                }}
              >
                {grant.isPending || grant.isSuccess
                  ? t('auth.oauthConsent.allowing')
                  : t('auth.oauthConsent.allow')}
              </button>
            )}
          </div>

          {leaving && (
            <p role="status" className="auth-status">
              {t('auth.oauthConsent.leaving', { host })}
            </p>
          )}
        </>
      )}

      <StepUpDialog
        purpose={StepUpRequestPurpose.PAT_CREATE}
        open={askingPassword}
        onProof={onProof}
        onCancel={() => setAskingPassword(false)}
      />
    </>
  );
}

/**
 * The `/oauth/authorize` route. A signed-out visitor's query is kept in the tab before sign-in
 * takes over, and put back when the visitor returns without it (Google sign-in drops long queries).
 */
export function OAuthConsentRoute() {
  const { t } = useTranslation();
  const token = useAuthToken();
  const sessionReady = useSessionReady();
  const { search } = useLocation();
  const navigate = useNavigate();
  const [restoreSettled, setRestoreSettled] = useState(false);

  useEffect(() => {
    if (sessionReady && !token) rememberConsentQuery(tabStorage, search);
  }, [sessionReady, token, search]);

  useEffect(() => {
    if (!sessionReady || !token) return;
    const restored = restoreConsentQuery(tabStorage, search);
    if (restored) navigate(`${CONSENT_PATH}${restored}`, { replace: true });
    setRestoreSettled(true);
  }, [sessionReady, token, search, navigate]);

  return (
    <RequireAuth>
      {restoreSettled || search !== '' ? (
        <OAuthConsentPage />
      ) : (
        <p role="status" className="auth-status">
          {t('auth.oauthConsent.loading')}
        </p>
      )}
    </RequireAuth>
  );
}
