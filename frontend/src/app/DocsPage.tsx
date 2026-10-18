import { useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { demoPagesEnabled } from './featureFlags';
import './DocsPage.css';

type Tab = 'overview' | 'api' | 'guides';

const TABS: ReadonlyArray<{ id: Tab; labelKey: string; to: string }> = [
  { id: 'overview', labelKey: 'common.docs.tabs.overview', to: '/docs' },
  { id: 'api', labelKey: 'common.docs.tabs.api', to: '/docs/api' },
  { id: 'guides', labelKey: 'common.docs.tabs.guides', to: '/docs/guides' },
];

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

const TAB_FROM_INITIAL: Record<NonNullable<DocsPageProps['initialTab']>, Tab> = {
  api: 'api',
  guides: 'guides',
};

export function DocsPage({ initialTab }: Readonly<DocsPageProps>) {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const urlTab = (searchParams.get('tab') as Tab | null) ?? null;
  const [local, setLocal] = useState<Tab | null>(initialTab ? TAB_FROM_INITIAL[initialTab] : null);
  const active: Tab = urlTab ?? local ?? 'overview';
  const showDemoPages = demoPagesEnabled();

  const onSelect = (next: Tab) => {
    setLocal(null);
    const params = new URLSearchParams(searchParams);
    if (next === 'overview') {
      params.delete('tab');
    } else {
      params.set('tab', next);
    }
    setSearchParams(params, { replace: true });
  };

  return (
    <main className="docs-page">
      <header className="docs-page__header">
        <p className="eyebrow">{t('common.docs.eyebrow')}</p>
        <h1>{t('common.docs.title')}</h1>
        <p className="docs-page__subtitle">{t('common.docs.subtitle')}</p>
      </header>

      <nav className="docs-page__nav" aria-label={t('common.docs.sectionsLabel')}>
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`docs-page__nav-link${active === tab.id ? ' is-active' : ''}`}
            aria-current={active === tab.id ? 'page' : undefined}
            onClick={() => onSelect(tab.id)}
          >
            {t(tab.labelKey)}
          </button>
        ))}
      </nav>

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
    </main>
  );
}
