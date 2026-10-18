import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { demoPagesEnabled } from './featureFlags';
import './DocsPage.css';

type Tab = 'overview' | 'api' | 'guides';

const TABS: ReadonlyArray<{ id: Tab; label: string; to: string }> = [
  { id: 'overview', label: 'Overview', to: '/docs' },
  { id: 'api', label: 'API Reference', to: '/docs/api' },
  { id: 'guides', label: 'Guides', to: '/docs/guides' },
];

export interface DocsPageProps {
  readonly initialTab?: 'api' | 'guides';
}

const TAB_FROM_INITIAL: Record<NonNullable<DocsPageProps['initialTab']>, Tab> = {
  api: 'api',
  guides: 'guides',
};

export function DocsPage({ initialTab }: Readonly<DocsPageProps>) {
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
        <p className="eyebrow">DOCUMENTATION</p>
        <h1>How FlowCortex works</h1>
        <p className="docs-page__subtitle">
          Concept guides, walk-throughs and the full HTTP API for the workflows you build.
        </p>
      </header>

      <nav className="docs-page__nav" aria-label="Docs sections">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`docs-page__nav-link${active === tab.id ? ' is-active' : ''}`}
            aria-current={active === tab.id ? 'page' : undefined}
            onClick={() => onSelect(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {active === 'overview' && (
        <section className="docs-page__section">
          <h2>Overview</h2>
          <p>
            FlowCortex is a runtime for AI workflows: a chain of typed steps that can call a model, wait
            for a human to approve, render a video, or post to a social account. Every workflow is a
            graph; every run is a journey through that graph.
          </p>
          <h3>Where to start</h3>
          <ul>
            <li><Link to="/docs/guides">Read a guided walk-through →</Link></li>
            <li><Link to="/docs/api">Browse the HTTP API →</Link></li>
            <li><Link to="/workflows">Open the workflow editor →</Link></li>
          </ul>
        </section>
      )}

      {active === 'api' && (
        <section className="docs-page__section">
          <h2>API Reference</h2>
          <p>
            The HTTP API is the same surface the Member and Admin apps use. Tokens come from{' '}
            <code>POST /api/v1/auth/login</code> and are sent as <code>Authorization: Bearer …</code> on
            every request.
          </p>

          <h3>Authentication</h3>
          <pre className="docs-page__snippet"><code>{`POST /api/v1/auth/login
Content-Type: application/json

{ "email": "you@example.com", "password": "••••••••" }

200 → { "accessToken": "eyJ…", "expiresIn": 3600 }`}</code></pre>

          <h3>Workflows</h3>
          <ul>
            <li><code>GET /api/v1/workflows</code> — list the workflows you can run.</li>
            <li><code>POST /api/v1/workflows/&#123;id&#125;/runs</code> — start a new run.</li>
            <li><code>GET /api/v1/runs/&#123;id&#125;</code> — read the current state of a run.</li>
            <li><code>GET /api/v1/runs/&#123;id&#125;/review-tasks</code> — list the approval checkpoints still open.</li>
          </ul>

          <p className="docs-page__hint">
            The full schema is published as <code>docs/openapi.yaml</code> and generated into the API client at
            <code> packages/api-client</code>. Use the generated types in your own app code.
          </p>
        </section>
      )}

      {active === 'guides' && (
        <section className="docs-page__section">
          <h2>Guides</h2>
          <ul>
            <li>
              <strong>Run your first pipeline.</strong> Open <Link to="/">Home</Link>, pick a template,
              send the input and watch the run update step by step.
            </li>
            <li>
              <strong>Approve a step.</strong> When a run hits an approval checkpoint, it stays{' '}
              <em>Waiting for review</em>
              {showDemoPages ? (
                <>
                  {' '}on the <Link to="/runs">Runs</Link> page until you act.
                </>
              ) : (
                ' on the run page until you act.'
              )}
            </li>
            {showDemoPages && (
              <li>
                <strong>Bring your own AI provider.</strong> Add a key on the{' '}
                <Link to="/integrations">Integrations</Link> page; any workflow that needs that model will
                pick it up automatically.
              </li>
            )}
          </ul>
        </section>
      )}
    </main>
  );
}
