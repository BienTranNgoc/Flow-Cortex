import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { ResendConfirmation } from './ConfirmEmailPage';
import { setPasswordSchema, type SetPasswordFormValues } from './registerSchema';
import {
  PASSWORD_LINK_INVALID,
  PASSWORD_SETUP_TOKEN_INVALID,
  failureMessage,
  fieldErrorsFrom,
  logout,
  problemCodeFrom,
  useAuthToken,
  useMeQuery,
  useOpenPasswordLinkMutation,
  useSetPasswordMutation,
} from './useAuth';

type FieldName = keyof SetPasswordFormValues;

const FIELDS: readonly {
  name: FieldName;
  labelKey: string;
  type: string;
  autoComplete: string;
}[] = [
  {
    name: 'firstName',
    labelKey: 'auth.setPassword.firstNameLabel',
    type: 'text',
    autoComplete: 'given-name',
  },
  {
    name: 'lastName',
    labelKey: 'auth.setPassword.lastNameLabel',
    type: 'text',
    autoComplete: 'family-name',
  },
  {
    name: 'username',
    labelKey: 'auth.setPassword.usernameLabel',
    type: 'text',
    autoComplete: 'username',
  },
  {
    name: 'password',
    labelKey: 'auth.setPassword.passwordLabel',
    type: 'password',
    autoComplete: 'new-password',
  },
  {
    name: 'confirmPassword',
    labelKey: 'auth.setPassword.confirmPasswordLabel',
    type: 'password',
    autoComplete: 'new-password',
  },
];

const SERVER_FIELDS: readonly FieldName[] = ['firstName', 'lastName', 'username', 'password'];

/**
 * Where an account without a password gets one. The one-time setup token arrives in router state
 * (after a confirmation or a set-password link), or as `?token=` in the emailed set-password link,
 * which this page spends to sign in. Without a usable token the page offers to email a new link.
 */
export function SetPasswordPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const linkToken = searchParams.get('token') ?? '';
  const location = useLocation();
  const navigate = useNavigate();
  const signedIn = Boolean(useAuthToken());
  const stateToken = (location.state as { passwordSetupToken?: string | null } | null)
    ?.passwordSetupToken;
  const [setupToken, setSetupToken] = useState<string | null>(stateToken ?? null);
  const [expired, setExpired] = useState(false);
  const openLink = useOpenPasswordLinkMutation();
  const { mutate: open } = openLink;
  // A link spends on first use, so StrictMode's second effect run must not send it again.
  const opened = useRef(false);

  useEffect(() => {
    if (!linkToken || opened.current) {
      return;
    }
    opened.current = true;
    open(linkToken, {
      onSuccess: ({ passwordSetupToken }) => {
        setSetupToken(passwordSetupToken);
        // Takes the spent link out of the address bar and the history entry.
        navigate('/set-password', { replace: true, state: { passwordSetupToken } });
      },
    });
  }, [linkToken, open, navigate]);

  let notice: string | null = null;
  if (openLink.isError) {
    notice =
      problemCodeFrom(openLink.error) === PASSWORD_LINK_INVALID
        ? t('auth.setPassword.linkInvalid')
        : (failureMessage(openLink, t, 'auth.setPassword.openFailed') ?? null);
  } else if (expired) {
    notice = t('auth.setPassword.expired');
  }

  let content;
  if (linkToken && !openLink.isError) {
    content = (
      <p role="status" className="auth-subtitle">
        {t('auth.setPassword.opening')}
      </p>
    );
  } else if (setupToken && signedIn) {
    content = (
      <SetPasswordForm
        setupToken={setupToken}
        onExpired={() => {
          setSetupToken(null);
          setExpired(true);
        }}
      />
    );
  } else {
    content = <EmailMeALink notice={notice} signedIn={signedIn} />;
  }

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">{t('auth.setPassword.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.setPassword.title')}</h1>
        {content}
      </section>
    </div>
  );
}

interface SetPasswordFormProps {
  setupToken: string;
  /** The server no longer accepts the token; the page falls back to emailing a new link. */
  onExpired: () => void;
}

function SetPasswordForm({ setupToken, onExpired }: SetPasswordFormProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const setPassword = useSetPasswordMutation();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SetPasswordFormValues>({
    resolver: zodResolver(setPasswordSchema),
    defaultValues: { firstName: '', lastName: '', username: '', password: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit(({ firstName, lastName, username, password }) => {
    setPassword.mutate(
      {
        passwordSetupToken: setupToken,
        password,
        firstName,
        lastName,
        ...(username === '' ? {} : { username }),
      },
      {
        onSuccess: () => navigate('/', { replace: true }),
        onError: (error) => {
          if (problemCodeFrom(error) === PASSWORD_SETUP_TOKEN_INVALID) {
            onExpired();
            return;
          }
          const refused = fieldErrorsFrom(error);
          let shown = 0;
          for (const field of SERVER_FIELDS) {
            const message = refused[field];
            if (message) {
              setError(field, { type: 'server', message }, { shouldFocus: shown === 0 });
              shown += 1;
            }
          }
        },
      },
    );
  });

  const refusedFields = fieldErrorsFrom(setPassword.error);
  const serverError =
    setPassword.isError && !SERVER_FIELDS.some((field) => refusedFields[field])
      ? (failureMessage(setPassword, t, 'auth.setPassword.saveFailed') ?? null)
      : null;

  return (
    <>
      <p className="auth-subtitle">{t('auth.setPassword.intro')}</p>

      <form
        onSubmit={onSubmit}
        noValidate
        aria-label={t('auth.setPassword.formLabel')}
        className="auth-form"
      >
        {FIELDS.map(({ name, labelKey, type, autoComplete }) => {
          const id = `set-password-${name}`;
          const error = errors[name];
          // Schema messages are locale keys; a server refusal arrives already worded and is shown as is.
          const message =
            error?.message && (error.type === 'server' ? error.message : t(error.message));
          return (
            <div className="field" key={name}>
              <label htmlFor={id}>{t(labelKey)}</label>
              <input
                id={id}
                type={type}
                autoComplete={autoComplete}
                aria-invalid={message ? 'true' : 'false'}
                aria-describedby={message ? `${id}-error` : undefined}
                {...register(name)}
              />
              {message && (
                <p id={`${id}-error`} role="alert">
                  {message}
                </p>
              )}
            </div>
          );
        })}
        {serverError && (
          <p role="alert" className="form-error">
            {serverError}
          </p>
        )}
        <button className="primary-button" type="submit" disabled={setPassword.isPending}>
          {setPassword.isPending ? t('auth.setPassword.submitting') : t('auth.setPassword.submit')}
        </button>
      </form>

      {serverError && (
        <p className="auth-footer">
          <Link to="/">{t('auth.setPassword.goToWorkspace')}</Link>
        </p>
      )}
    </>
  );
}

interface EmailMeALinkProps {
  notice: string | null;
  signedIn: boolean;
}

/** No usable setup token here: a new set-password link goes to the account's address. */
function EmailMeALink({ notice, signedIn }: EmailMeALinkProps) {
  const { t } = useTranslation();
  const me = useMeQuery();
  const navigate = useNavigate();
  // Signed in, the link goes to the account's own address; the page waits until it is known.
  const waitingForMe = signedIn && me.isPending;
  const canSkip = signedIn && me.data?.passwordSetupRequired === false;

  // The login page sends a signed-in visitor away, so it opens only once the session is gone.
  const onSignOut = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    void logout().then(() => navigate('/login', { replace: true }));
  };

  return (
    <>
      {notice && (
        <p role="alert" className="form-error">
          {notice}
        </p>
      )}
      <p className="auth-subtitle">{t('auth.setPassword.linkHint')}</p>
      {waitingForMe ? (
        <output>{t('auth.session.checking')}</output>
      ) : (
        <ResendConfirmation
          email={signedIn ? me.data?.email : undefined}
          action={t('auth.setPassword.emailLink')}
          sentMessage={t('auth.setPassword.linkSent')}
        />
      )}
      <p className="auth-footer">
        {signedIn ? (
          canSkip ? (
            <Link to="/">{t('auth.setPassword.goToWorkspace')}</Link>
          ) : (
            !waitingForMe && (
              <Link to="/login" onClick={onSignOut}>
                {t('auth.setPassword.signOut')}
              </Link>
            )
          )
        ) : (
          <Link to="/login">{t('auth.setPassword.backToSignIn')}</Link>
        )}
      </p>
    </>
  );
}
