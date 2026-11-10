import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { usernameSchema } from './profileSchema';
import { failureMessage } from './useAuth';
import { useChangeUsername } from './useOwnProfile';

interface Props {
  username: string | undefined;
  nextChangeAt: string | undefined;
}

/** The refusal belongs under the field: the 422 names it in `errors.username`, a 409 only in `detail`. */
function fieldError(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  return error.problem?.errors?.username ?? error.problem?.detail;
}

export function UsernameField({ username, nextChangeAt }: Props) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(username ?? '');
  const [formatError, setFormatError] = useState<string>();
  const mutation = useChangeUsername();

  const locked = Boolean(nextChangeAt) && new Date(nextChangeAt!).getTime() > Date.now();
  const serverError = fieldError(mutation.error) ?? failureMessage(mutation, t);
  const error = formatError ?? serverError;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = usernameSchema.safeParse(draft);
    if (!parsed.success) {
      setFormatError(t(parsed.error.issues[0]!.message));
      return;
    }
    setFormatError(undefined);
    mutation.mutate(parsed.data);
  };

  return (
    <form onSubmit={onSubmit} noValidate className="profile-username">
      <div className="field">
        <label htmlFor="profile-username">{t('auth.profile.usernameLabel')}</label>
        <input
          id="profile-username"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            setFormatError(undefined);
            mutation.reset();
          }}
          disabled={locked}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby="profile-username-hint"
        />
        <p id="profile-username-hint" className="profile-card__hint">
          {locked
            ? t('auth.profile.usernameLocked', {
                date: formatDateTime(nextChangeAt!, activeLocale(), { dateStyle: 'medium' }),
              })
            : t('auth.profile.usernameHint')}
        </p>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
      </div>
      {mutation.isSuccess && (
        <p className="profile-saved" role="status">
          {t('auth.profile.usernameSaved')}
        </p>
      )}
      <div className="profile-form__actions">
        <button
          type="submit"
          className="cortex-btn cortex-btn--primary"
          disabled={locked || mutation.isPending || draft === (username ?? '')}
        >
          {mutation.isPending ? t('auth.profile.saving') : t('auth.profile.usernameSave')}
        </button>
      </div>
    </form>
  );
}
