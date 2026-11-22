import { ApiError, type ContentDocumentSummary } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Timestamp } from '../../app/AdminList';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import './content.css';
import { documentTitle } from './documentTitle';
import { useDocumentsQuery } from './useContentDocuments';

export function DocumentsPage() {
  const { t, i18n } = useTranslation();
  usePageTitle(t('content.documents.title'));
  const query = useDocumentsQuery();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;

  return (
    <main>
      <PageHeading
        eyebrow={t('content.eyebrow')}
        title={t('content.documents.title')}
        description={t('content.documents.description')}
      />

      {disabled && (
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      )}
      {error && !disabled && (
        <div className="cortex-admin-error">
          <p role="alert">{t('content.documents.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {query.isPending && (
        <output className="cortex-admin-loading">{t('content.documents.loading')}</output>
      )}
      {data && error && <p className="cortex-admin-stale">{t('common.shell.list.stale')}</p>}
      {data?.length === 0 && (
        <EmptyState
          title={t('content.documents.empty')}
          description={t('content.documents.emptyHint')}
        />
      )}
      {data && data.length > 0 && (
        <TableRegion label={t('content.documents.tableLabel')}>
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">{t('content.documents.columns.document')}</th>
                <th scope="col">{t('content.documents.columns.draft')}</th>
                <th scope="col">{t('content.documents.columns.published')}</th>
              </tr>
            </thead>
            <tbody>
              {data.map((document) => (
                <DocumentRow key={document.slug} document={document} language={i18n.language} />
              ))}
            </tbody>
          </table>
        </TableRegion>
      )}
    </main>
  );
}

function DocumentRow({
  document,
  language,
}: Readonly<{ document: ContentDocumentSummary; language: string }>) {
  const { t } = useTranslation();
  return (
    <tr>
      <td data-label={t('content.documents.columns.document')}>
        <Link to={`/content/documents/${document.slug}`}>{documentTitle(document, language)}</Link>
        <span className="cortex-admin-table-job-message">{document.slug}</span>
      </td>
      <td data-label={t('content.documents.columns.draft')}>
        {document.draftVersion > 0 ? (
          <>
            {t('content.documents.version', { version: document.draftVersion })}
            <span className="cortex-admin-table-job-message">
              <Timestamp value={document.draftUpdatedAt ?? undefined} />
            </span>
          </>
        ) : (
          t('content.documents.noDraft')
        )}
      </td>
      <td data-label={t('content.documents.columns.published')}>
        {document.publishedVersion > 0 ? (
          <>
            {t('content.documents.version', { version: document.publishedVersion })}
            <span className="cortex-admin-table-job-message">
              <Timestamp value={document.publishedAt ?? undefined} />
            </span>
          </>
        ) : (
          t('content.documents.notPublished')
        )}
      </td>
    </tr>
  );
}
