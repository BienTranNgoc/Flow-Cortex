import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pager, Timestamp } from '../../app/AdminList';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { useRevisionsQuery } from './useContentDocuments';

interface RevisionsPanelProps {
  readonly slug: string;
  /** Show "Load into editor": only a caller who may edit can use a loaded snapshot. */
  readonly canLoad: boolean;
  /** The snapshot being read, so its button and the others hold still. */
  readonly loadingId: string | null;
  readonly onLoad: (id: string) => void;
}

/** Every snapshot of the document, newest first: each save writes a draft one, each publish a publication one. */
export function RevisionsPanel({ slug, canLoad, loadingId, onLoad }: RevisionsPanelProps) {
  const { t } = useTranslation();
  const headingId = useId();
  const [page, setPage] = useState(0);
  const query = useRevisionsQuery(slug, page);
  const { data, error } = query;

  return (
    <section className="content-revisions" aria-labelledby={headingId}>
      <h2 id={headingId}>{t('content.revisions.title')}</h2>
      {error && (
        <div className="cortex-admin-error">
          <p role="alert">{t('content.revisions.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {query.isPending && (
        <output className="cortex-admin-loading">{t('content.revisions.loading')}</output>
      )}
      {data && error && <p className="cortex-admin-stale">{t('common.shell.list.stale')}</p>}
      {data?.items.length === 0 && (
        <div className="cortex-admin-empty">
          <p>{t('content.revisions.empty')}</p>
        </div>
      )}
      {data && data.items.length > 0 && (
        <TableRegion label={t('content.revisions.tableLabel')} busy={query.isPlaceholderData}>
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">{t('content.revisions.columns.kind')}</th>
                <th scope="col">{t('content.revisions.columns.version')}</th>
                <th scope="col">{t('content.revisions.columns.author')}</th>
                <th scope="col">{t('content.revisions.columns.time')}</th>
                {canLoad && (
                  <th scope="col">
                    <span className="content-visually-hidden">
                      {t('content.revisions.columns.actions')}
                    </span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {data.items.map((revision) => (
                <tr key={revision.id}>
                  <td data-label={t('content.revisions.columns.kind')}>
                    <StatusPill
                      tone={revision.kind === 'publication' ? 'success' : 'muted'}
                      label={t(`content.revisions.kind.${revision.kind}`)}
                    />
                  </td>
                  <td data-label={t('content.revisions.columns.version')}>
                    {t('content.documents.version', { version: revision.version })}
                  </td>
                  <td data-label={t('content.revisions.columns.author')}>
                    {revision.authorEmail ?? t('content.revisions.unknownAuthor')}
                  </td>
                  <td data-label={t('content.revisions.columns.time')}>
                    <Timestamp value={revision.createdAt} />
                  </td>
                  {canLoad && (
                    <td>
                      <button
                        type="button"
                        className="cortex-admin-btn cortex-admin-btn--sm"
                        aria-disabled={loadingId !== null}
                        aria-label={t('content.revisions.loadAria', {
                          kind: t(`content.revisions.kind.${revision.kind}`),
                          version: revision.version,
                        })}
                        onClick={() => loadingId === null && onLoad(revision.id)}
                      >
                        {loadingId === revision.id
                          ? t('content.revisions.loadingRevision')
                          : t('content.revisions.load')}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </TableRegion>
      )}
      {data && (data.items.length > 0 || data.page > 0) && (
        <Pager
          page={data.page}
          size={data.size}
          total={data.total}
          nounKey="content.revisions.noun"
          onPageChange={setPage}
        />
      )}
    </section>
  );
}
