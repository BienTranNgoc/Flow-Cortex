import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import './content.css';
import { contentProblem } from './contentProblems';
import { DocumentEditor } from './DocumentEditor';
import { documentTitle } from './documentTitle';
import { useDocumentsQuery, useDraftQuery } from './useContentDocuments';

export function DocumentEditorPage() {
  const { t, i18n } = useTranslation();
  const { slug = '' } = useParams();
  const documents = useDocumentsQuery();
  const draft = useDraftQuery(slug);
  const me = useMeQuery().data;
  const summary = documents.data?.find((document) => document.slug === slug);
  const title = summary ? documentTitle(summary, i18n.language) : t('content.editor.fallbackTitle');
  usePageTitle(title);

  const notFound =
    (documents.data !== undefined && summary === undefined) ||
    contentProblem(draft.error)?.code === 'document-not-found';
  const failed = Boolean(documents.error || draft.error);

  let content;
  if (notFound) {
    content = (
      <div className="cortex-admin-error">
        <p role="alert">{t('content.editor.notFound')}</p>
      </div>
    );
  } else if (!summary || !draft.data) {
    content = failed ? (
      <div className="cortex-admin-error">
        <p role="alert">{t('content.editor.loadError')}</p>
        <button
          type="button"
          className="cortex-admin-btn"
          onClick={() => {
            void documents.refetch();
            void draft.refetch();
          }}
        >
          {t('common.shell.list.retry')}
        </button>
      </div>
    ) : (
      <output className="cortex-admin-loading">{t('content.editor.loading')}</output>
    );
  } else {
    content = (
      <>
        {failed && <p className="cortex-admin-stale">{t('content.editor.refreshFailed')}</p>}
        <DocumentEditor key={slug} slug={slug} summary={summary} draft={draft.data} me={me} />
      </>
    );
  }

  return (
    <main className="content-editor-page">
      <Link className="cortex-admin-back-link" to="/content/documents">
        {t('content.editor.backToList')}
      </Link>
      <header className="cortex-admin-page-heading content-editor-page__heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('content.eyebrow')}</p>
          <h1>{title}</h1>
          <p className="cortex-admin-page-description">{slug}</p>
        </div>
      </header>
      {content}
    </main>
  );
}
