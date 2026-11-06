import { AdminSupportKbArticleSummaryStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminList } from '../../../app/AdminList';
import { usePageTitle } from '../../../app/usePageTitle';
import { RequestTime } from '../requests/RequestTime';
import './kb.css';
import { useArticlesQuery } from './useKnowledgeBase';

const STATUSES = Object.values(AdminSupportKbArticleSummaryStatus);
const FEATURED_SLOTS = 3;

export function ArticlesPage() {
  const { t } = useTranslation();
  usePageTitle(t('support.articles.title'));
  const [status, setStatus] = useState<AdminSupportKbArticleSummaryStatus | ''>('');
  const query = useArticlesQuery(status);
  const featuredCount = query.data?.publishedFeaturedCount;
  const statusLabel = (value: string) =>
    STATUSES.includes(value as AdminSupportKbArticleSummaryStatus)
      ? t(`support.articleStatus.${value}`)
      : value;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.articles.eyebrow')}</p>
          <h1>{t('support.articles.title')}</h1>
          <p className="cortex-admin-page-description">{t('support.articles.description')}</p>
        </div>
        <Link className="support-link-button" to="/support/articles/new">
          {t('support.articles.newArticle')}
        </Link>
      </div>

      {featuredCount !== undefined && featuredCount < FEATURED_SLOTS && (
        <div className="support-warning">
          <strong>{t('support.articles.featuredShort', { n: featuredCount })}</strong>
          <span>{t('support.articles.featuredHint')}</span>
        </div>
      )}

      <AdminList
        query={query}
        nounKey="support.articles.noun"
        tableLabelKey="support.articles.tableLabel"
        statuses={STATUSES}
        statusLabel={statusLabel}
        status={status}
        onStatusChange={(next) => setStatus(next as AdminSupportKbArticleSummaryStatus | '')}
        onPageChange={() => undefined}
      >
        {(articles) => (
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">{t('support.articles.columns.title')}</th>
                <th scope="col">{t('support.articles.columns.group')}</th>
                <th scope="col">{t('support.articles.columns.status')}</th>
                <th scope="col">{t('support.articles.columns.featured')}</th>
                <th scope="col">{t('support.articles.columns.updated')}</th>
              </tr>
            </thead>
            <tbody>
              {articles.map((article) => (
                <tr key={article.id}>
                  <td data-label={t('support.articles.columns.title')}>
                    <Link to={`/support/articles/${article.id}`}>{article.title}</Link>
                    <span className="cortex-admin-table-job-message">{article.slug}</span>
                  </td>
                  <td data-label={t('support.articles.columns.group')}>{article.groupName}</td>
                  <td data-label={t('support.articles.columns.status')}>
                    <span
                      className={
                        article.status === 'published'
                          ? 'cortex-admin-pill cortex-admin-pill--success'
                          : 'cortex-admin-pill cortex-admin-pill--muted'
                      }
                    >
                      {t(`support.articleStatus.${article.status}`)}
                    </span>
                  </td>
                  <td data-label={t('support.articles.columns.featured')}>
                    {article.featuredPosition
                      ? t('support.articles.featuredPosition', { position: article.featuredPosition })
                      : t('support.common.noValue')}
                  </td>
                  <td data-label={t('support.articles.columns.updated')}>
                    <RequestTime value={article.updatedAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
