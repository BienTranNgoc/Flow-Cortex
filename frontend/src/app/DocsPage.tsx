import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { demoPagesEnabled } from './featureFlags';
import { PageHeading } from './PageHeading';
import './DocsPage.css';

type Tab = 'overview' | 'api' | 'guides';

const AUTH_SNIPPET = `POST /api/v1/auth/login
Content-Type: application/json

{ "email": "you@example.com", "password": "••••••••" }

200 → { "accessToken": "eyJ…", "expiresIn": 3600 }`;

const ENDPOINTS = [
  { route: 'GET /api/v1/workflows', key: 'common.docs.api.listWorkflows' },
  { route: 'POST /api/v1/workflows/{id}/runs', key: 'common.docs.api.startRun' },
  { route: 'GET /api/v1/runs/{id}', key: 'common.docs.api.readRun' },
  { route: 'GET /api/v1/runs/{id}/review-tasks', key: 'common.docs.api.listReviewTasks' },
] as const;

const CODE = <code />;

export interface DocsPageProps {
  readonly initialTab?: 'api' | 'guides';
}

const LEGACY_TAB_PATH: Record<string, string> = { api: '/docs/api', guides: '/docs/guides' };

export function DocsPage({ initialTab }: Readonly<DocsPageProps>) {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const legacyPath = LEGACY_TAB_PATH[searchParams.get('tab') ?? ''];
  const active: Tab = initialTab ?? 'overview';
  const showDemoPages = demoPagesEnabled();

  if (legacyPath) {
    return <Navigate to={legacyPath} replace />;
  }

  return (
    <div className="docs-page">
      <PageHeading title={t('common.docs.title')} lede={t('common.docs.subtitle')} />

      {active === 'overview' && (
        <section className="docs-page__section">
          <h2>{t('common.docs.tabs.overview')}</h2>
          <p>{t('common.docs.overview.body')}</p>
          <h3>{t('common.docs.overview.whereToStart')}</h3>
          <ul>
            <li><Link to="/docs/guides">{t('common.docs.overview.readGuide')}</Link></li>
            <li><Link to="/docs/api">{t('common.docs.overview.browseApi')}</Link></li>
            <li><Link to="/workflows">{t('common.docs.overview.openEditor')}</Link></li>
          </ul>
        </section>
      )}

      {active === 'api' && (
        <section className="docs-page__section">
          <h2>{t('common.docs.tabs.api')}</h2>
          <p>
            <Trans i18nKey="common.docs.api.intro" components={{ code: CODE }} />
          </p>

          <h3>{t('common.docs.api.authentication')}</h3>
          <pre className="docs-page__snippet"><code>{AUTH_SNIPPET}</code></pre>

          <h3>{t('common.docs.api.workflows')}</h3>
          <ul>
            {ENDPOINTS.map(({ route, key }) => (
              <li key={route}>
                <code>{route}</code> {t(key)}
              </li>
            ))}
          </ul>

          <p className="docs-page__hint">
            <Trans i18nKey="common.docs.api.schemaHint" components={{ code: CODE }} />
          </p>
        </section>
      )}

      {active === 'guides' && (
        <section className="docs-page__section">
          <h2>{t('common.docs.tabs.guides')}</h2>
          <ul>
            <li>
              <Trans
                i18nKey="common.docs.guides.firstRun"
                components={{ strong: <strong />, homeLink: <Link to="/" /> }}
              />
            </li>
            <li>
              <Trans
                i18nKey={
                  showDemoPages ? 'common.docs.guides.approveOnRuns' : 'common.docs.guides.approveOnRun'
                }
                components={{ strong: <strong />, em: <em />, runsLink: <Link to="/runs" /> }}
              />
            </li>
            {showDemoPages && (
              <li>
                <Trans
                  i18nKey="common.docs.guides.byoProvider"
                  components={{ strong: <strong />, integrationsLink: <Link to="/integrations" /> }}
                />
              </li>
            )}
          </ul>
        </section>
      )}
    </div>
  );
}
