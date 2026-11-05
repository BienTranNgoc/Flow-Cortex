import { useId } from 'react';
import { LoadFailed } from './GuidesTab';
import { supportCopy } from './supportCopy';
import { useSupportPanel } from './supportPanelState';
import { useFeaturedArticles, useSupportContact } from './useSupportKb';

/** "Cần người hỗ trợ? Gửi email tới …" — renders nothing when no contact is configured. */
export function ContactLine() {
  const email = useSupportContact();
  if (!email) return null;
  return (
    <p className="support-contact">
      {supportCopy.contact.lead} <a href={`mailto:${email}`}>{supportCopy.contact.sendTo(email)}</a>
    </p>
  );
}

function StartArticles() {
  const { dispatch } = useSupportPanel();
  const featured = useFeaturedArticles();
  const headingId = useId();

  if (featured.isSuccess && featured.data.length === 0) return null;

  return (
    <section className="support-section">
      <h3 id={headingId} className="support-section__title">
        {supportCopy.home.startHeading}
      </h3>
      {featured.isPending && (
        <p className="support-state" role="status">
          {supportCopy.common.loading}
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

/** Home: "Bắt đầu" articles, suggested questions, the contact line and, for a member, a request shortcut. */
export function HomeTab() {
  const { dispatch, member } = useSupportPanel();
  const suggestedId = useId();

  return (
    <div className="support-home">
      <p className="support-home__greeting">{supportCopy.home.greeting}</p>
      <StartArticles />
      <section className="support-section">
        <h3 id={suggestedId} className="support-section__title">
          {supportCopy.home.suggestedHeading}
        </h3>
        <ul className="support-list" aria-labelledby={suggestedId}>
          {supportCopy.home.suggestedQuestions.map((question) => (
            <li key={question}>
              <button
                type="button"
                className="support-list__item"
                onClick={() => dispatch({ type: 'ask', question })}
              >
                {question}
              </button>
            </li>
          ))}
        </ul>
      </section>
      {member && (
        <button
          type="button"
          className="support-button support-button--primary"
          onClick={() => dispatch({ type: 'startRequest' })}
        >
          {supportCopy.home.newRequest}
        </button>
      )}
      <ContactLine />
    </div>
  );
}
