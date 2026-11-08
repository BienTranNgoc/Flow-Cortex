import { ApiError } from '@flow-cortex/api-client';
import { Markdown } from '@flow-cortex/ui-kit';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { problemMessage } from './supportProblems';
import { useSupportPanel } from './supportPanelState';
import {
  searchTerm,
  supportProblemCode,
  useKbArticle,
  useKbGroups,
  useKbSearch,
} from './useSupportKb';

const SEARCH_DELAY_MS = 300;

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/** Failed read: the support problem's own message when it has one, else a generic one with retry. */
export function LoadFailed({ error, onRetry }: Readonly<{ error: unknown; onRetry: () => void }>) {
  const { t } = useTranslation();
  const code = supportProblemCode(error);
  const message =
    code === 'support-disabled' ? problemMessage(code, {}, t) : t('support.common.loadFailed');
  return (
    <div className="support-state" role="alert">
      <p>{message}</p>
      <button type="button" className="support-button" onClick={onRetry}>
        {t('support.common.retry')}
      </button>
    </div>
  );
}

/** A failed re-read of content already on screen: says so and offers a retry without replacing it. */
export function RefreshFailed({ onRetry }: Readonly<{ onRetry: () => void }>) {
  const { t } = useTranslation();
  return (
    <div className="support-state" role="alert">
      <p>{t('support.common.refreshFailed')}</p>
      <button type="button" className="support-button" onClick={onRetry}>
        {t('support.common.retry')}
      </button>
    </div>
  );
}

function Loading() {
  const { t } = useTranslation();
  return (
    <p className="support-state" role="status">
      {t('support.common.loading')}
    </p>
  );
}

function AskAssistant({ message }: Readonly<{ message: string }>) {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  return (
    <div className="support-state">
      <p>{message}</p>
      <button
        type="button"
        className="support-button"
        onClick={() => dispatch({ type: 'selectTab', tab: 'assistant' })}
      >
        {t('support.guides.askAssistant')}
      </button>
    </div>
  );
}

function GroupList() {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const groups = useKbGroups();
  const baseId = useId();

  if (groups.isPending) return <Loading />;
  if (groups.isError) return <LoadFailed error={groups.error} onRetry={() => groups.refetch()} />;
  if (groups.data.length === 0) return <AskAssistant message={t('support.guides.empty')} />;

  return groups.data.map((group) => {
    const headingId = `${baseId}-${group.id}`;
    return (
      <section key={group.id} className="support-section">
        <h3 id={headingId} className="support-section__title">
          {group.name}
        </h3>
        <ul className="support-list" aria-labelledby={headingId}>
          {group.articles.map((article) => (
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
      </section>
    );
  });
}

function SearchResults({ term }: Readonly<{ term: string }>) {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const results = useKbSearch(term);
  const headingId = useId();

  if (results.isPending) return <Loading />;
  if (results.isError)
    return <LoadFailed error={results.error} onRetry={() => results.refetch()} />;
  if (results.data.length === 0) return <AskAssistant message={t('support.guides.noMatch')} />;

  return (
    <section className="support-section">
      <h3 id={headingId} className="support-section__title">
        {t('support.guides.resultsHeading')}
      </h3>
      <ul className="support-list" aria-labelledby={headingId}>
        {results.data.map((hit) => (
          <li key={hit.slug}>
            <button
              type="button"
              className="support-list__item"
              onClick={() => dispatch({ type: 'openArticle', slug: hit.slug })}
            >
              <span>{hit.title}</span>
              <span className="support-list__meta">{hit.groupName}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Guides: published articles by topic group, with keyword search. */
export function GuidesTab() {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const liveTerm = searchTerm(query);
  const debouncedTerm = useDebounced(liveTerm, SEARCH_DELAY_MS);
  // Only a search waits for the pause in typing; dropping below the minimum shows the groups at once.
  const term = liveTerm === null ? null : debouncedTerm;
  const inputId = useId();

  return (
    <div className="support-guides">
      <form className="support-search" role="search" onSubmit={(event) => event.preventDefault()}>
        <label htmlFor={inputId} className="support-search__label">
          {t('support.guides.searchLabel')}
        </label>
        <input
          id={inputId}
          type="search"
          className="support-search__input"
          value={query}
          placeholder={t('support.guides.searchPlaceholder')}
          maxLength={200}
          onChange={(event) => setQuery(event.target.value)}
        />
      </form>
      {term === null ? <GroupList /> : <SearchResults term={term} />}
    </div>
  );
}

/** A published article shown inside the panel, over the tab it was opened from. */
export function ArticleView({ slug }: Readonly<{ slug: string }>) {
  const { t } = useTranslation();
  const { dispatch, member } = useSupportPanel();
  const article = useKbArticle(slug);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);
  const goneRef = useRef<HTMLButtonElement>(null);
  const gone = article.error instanceof ApiError && article.error.status === 404;
  const status = article.isSuccess ? 'loaded' : gone ? 'gone' : 'other';

  // The button that opened the article is gone from the DOM, so focus must land somewhere inside.
  useEffect(() => {
    const target = status === 'loaded' ? headingRef : status === 'gone' ? goneRef : backRef;
    target.current?.focus();
  }, [status]);

  const back = (
    <button
      ref={backRef}
      type="button"
      className="support-back"
      onClick={() => dispatch({ type: 'closeArticle' })}
    >
      {t('support.common.back')}
    </button>
  );

  if (article.isPending) {
    return (
      <div className="support-article">
        {back}
        <Loading />
      </div>
    );
  }
  if (article.isError) {
    if (gone) {
      return (
        <div className="support-article support-state">
          <p role="status">{t('support.article.notPublished')}</p>
          <button
            ref={goneRef}
            type="button"
            className="support-button"
            onClick={() => dispatch({ type: 'selectTab', tab: member ? 'guides' : 'home' })}
          >
            {member ? t('support.article.backToGuides') : t('support.article.backToHome')}
          </button>
        </div>
      );
    }
    return (
      <div className="support-article">
        {back}
        <LoadFailed error={article.error} onRetry={() => article.refetch()} />
      </div>
    );
  }

  return (
    <article className="support-article">
      {back}
      <h3 ref={headingRef} tabIndex={-1} className="support-article__title">
        {article.data.title}
      </h3>
      <Markdown>{article.data.body}</Markdown>
    </article>
  );
}
