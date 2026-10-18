import { useQuery } from '@tanstack/react-query';
import { Settings2 } from 'lucide-react';
import { PageHeading } from '../../app/PageHeading';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchFeatureFlags } from '../../app/mockAdminApi';

export function FeaturesPage() {
  usePageTitle('Feature Switches');
  const query = useQuery({ queryKey: ['admin', 'features'], queryFn: fetchFeatureFlags });
  const flags = query.data ?? [];

  return (
    <div>
      <PageHeading
        eyebrow="Administration"
        title="Feature Switches"
        description="Toggle the runtime feature flags served by GET /api/v1/features. Changes apply immediately for every user."
        actions={
          <button type="button" className="cortex-admin-btn cortex-admin-btn--ghost">
            <Settings2 size={16} aria-hidden="true" />
            <span>Reload flags</span>
          </button>
        }
      />

      {query.isLoading && <output className="cortex-admin-loading">Loading flags…</output>}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load feature flags.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}

      {flags.length > 0 && (
        <div className="cortex-admin-stack-list">
          {flags.map((f) => (
            <article key={f.key} className="cortex-admin-panel">
              <header className="cortex-admin-section-heading">
                <div>
                  <h2>
                    <code className="cortex-admin-table-job-id">{f.key}</code>
                  </h2>
                  <p>{f.description}</p>
                </div>
                <ToggleChip enabled={f.enabled} />
              </header>
              <p className="cortex-admin-stale">
                Last changed by <strong>{f.updatedBy}</strong> ·{' '}
                <Timestamp value={f.updatedAt} />
              </p>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

function ToggleChip({ enabled }: Readonly<{ enabled: boolean }>) {
  return (
    <label className="cortex-admin-toggle">
      <input type="checkbox" defaultChecked={enabled} aria-label="Toggle feature flag" />
      <span className="cortex-admin-toggle__track" aria-hidden="true">
        <span className="cortex-admin-toggle__thumb" />
      </span>
      <span className="cortex-admin-toggle__label">{enabled ? 'ON' : 'OFF'}</span>
    </label>
  );
}
