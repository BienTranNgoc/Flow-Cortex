import { AdminSupportKbArticleSummaryStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AdminList } from '../../../app/AdminList';
import { usePageTitle } from '../../../app/usePageTitle';
import { RequestTime } from '../requests/RequestTime';
import { supportCopy } from '../supportCopy';
import './kb.css';
import { useArticlesQuery } from './useKnowledgeBase';

const STATUSES = Object.values(AdminSupportKbArticleSummaryStatus);
const FEATURED_SLOTS = 3;
const copy = supportCopy.articles;
const statusLabel = (status: string) =>
  status in supportCopy.articleStatus
    ? supportCopy.articleStatus[status as AdminSupportKbArticleSummaryStatus]
    : status;

export function ArticlesPage() {
  usePageTitle(copy.title);
  const [status, setStatus] = useState<AdminSupportKbArticleSummaryStatus | ''>('');
  const query = useArticlesQuery(status);
  const featuredCount = query.data?.publishedFeaturedCount;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{copy.eyebrow}</p>
          <h1>{copy.title}</h1>
          <p className="cortex-admin-page-description">{copy.description}</p>
        </div>
        <Link className="support-link-button" to="/support/articles/new">
          {copy.newArticle}
        </Link>
      </div>

      {featuredCount !== undefined && featuredCount < FEATURED_SLOTS && (
        <div className="support-warning">
          <strong>{copy.featuredShort(featuredCount)}</strong>
          <span>{copy.featuredHint}</span>
        </div>
      )}

      <AdminList
        query={query}
        noun={copy.noun}
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
                <th scope="col">{copy.columns.title}</th>
                <th scope="col">{copy.columns.group}</th>
                <th scope="col">{copy.columns.status}</th>
                <th scope="col">{copy.columns.featured}</th>
                <th scope="col">{copy.columns.updated}</th>
              </tr>
            </thead>
            <tbody>
              {articles.map((article) => (
                <tr key={article.id}>
                  <td data-label={copy.columns.title}>
                    <Link to={`/support/articles/${article.id}`}>{article.title}</Link>
                    <span className="cortex-admin-table-job-message">{article.slug}</span>
                  </td>
                  <td data-label={copy.columns.group}>{article.groupName}</td>
                  <td data-label={copy.columns.status}>
                    <span
                      className={
                        article.status === 'published'
                          ? 'cortex-admin-pill cortex-admin-pill--success'
                          : 'cortex-admin-pill cortex-admin-pill--muted'
                      }
                    >
                      {supportCopy.articleStatus[article.status]}
                    </span>
                  </td>
                  <td data-label={copy.columns.featured}>
                    {article.featuredPosition
                      ? copy.featuredPosition(article.featuredPosition)
                      : supportCopy.common.noValue}
                  </td>
                  <td data-label={copy.columns.updated}>
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
