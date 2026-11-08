import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { LoadFailed } from './GuidesTab';
import { useSupportPanel } from './supportPanelState';
import { useFeaturedArticles, useSupportContact } from './useSupportKb';

const SUGGESTED_QUESTIONS = ['credit', 'firstRun', 'runFailed'] as const;

/** "Need help from a person? Email …" — renders nothing when no contact is configured. */
export function ContactLine() {
  const { t } = useTranslation();
  const email = useSupportContact();
  if (!email) return null;
  return (
    <p className="support-contact">
      {t('support.contact.lead')} <a href={`mailto:${email}`}>{t('support.contact.sendTo', { email })}</a>
    </p>
  );
}

function StartArticles() {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const featured = useFeaturedArticles();
  const headingId = useId();

  if (featured.isSuccess && featured.data.length === 0) return null;

  return (
    <section className="support-section">
      <h3 id={headingId} className="support-section__title">
        {t('support.home.startHeading')}
      </h3>
      {featured.isPending && (
        <p className="support-state" role="status">
          {t('support.common.loading')}
        </p>
      )}
      {featured.isError && <LoadFailed error={featured.error} onRetry={() => featured.refetch()} />}
      {featured.isSuccess && (
        <ul className="support-list" aria-labelledby={headingId}>
          {featured.data.map((article) => (
            <li key={article.slug}>
              <button
                type="button"
                className="support-list__item"
                onClick={() => dispatch({ type: 'openArticle', slug: article.slug })}
              >
                {article.title}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Home: "Get started" articles, suggested questions, the contact line and, for a member, a request shortcut. */
export function HomeTab() {
  const { t } = useTranslation();
  const { dispatch, member } = useSupportPanel();
  const suggestedId = useId();

  return (
    <div className="support-home">
      <p className="support-home__greeting">{t('support.home.greeting')}</p>
      <StartArticles />
      <section className="support-section">
        <h3 id={suggestedId} className="support-section__title">
          {t('support.home.suggestedHeading')}
        </h3>
        <ul className="support-list" aria-labelledby={suggestedId}>
          {SUGGESTED_QUESTIONS.map((key) => {
            const question = t(`support.home.suggestedQuestions.${key}`);
            return (
              <li key={key}>
                <button
                  type="button"
                  className="support-list__item"
                  onClick={() => dispatch({ type: 'ask', question })}
                >
                  {question}
                </button>
              </li>
            );
          })}
        </ul>
      </section>
      {member && (
        <button
          type="button"
          className="support-button support-button--primary"
          onClick={() => dispatch({ type: 'startRequest' })}
        >
          {t('support.home.newRequest')}
        </button>
      )}
      <ContactLine />
    </div>
  );
}
