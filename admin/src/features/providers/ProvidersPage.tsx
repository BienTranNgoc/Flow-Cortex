import { useQuery } from '@tanstack/react-query';
import { Activity, Gauge, Zap } from 'lucide-react';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchModelProviders } from '../../app/mockAdminApi';

export function ProvidersPage() {
  usePageTitle('Model Providers');
  const query = useQuery({ queryKey: ['admin', 'providers'], queryFn: fetchModelProviders });
  const providers = query.data ?? [];
  const total = providers.length;
  const operational = providers.filter((p) => p.status === 'Operational').length;
  const totalTokens = providers.reduce((sum, p) => sum + p.tokensToday, 0);
  const totalCost = providers.reduce((sum, p) => sum + p.estimatedCostUsd, 0);

  return (
    <div>
      <PageHeading
        eyebrow="Credits & Billing"
        title="Model Providers"
        description="Latency, token usage, and rate limits for every upstream model provider."
        actions={
          <button type="button" className="cortex-admin-btn cortex-admin-btn--ghost">
            <Activity size={16} aria-hidden="true" />
            <span>Ping all</span>
          </button>
        }
      />

      <section className="cortex-admin-metric-grid" aria-label="Provider statistics">
        <MetricCard label="Providers" value={total} />
        <MetricCard label="Operational" value={operational} hint="Healthy and ready" />
        <MetricCard label="Tokens today" value={totalTokens.toLocaleString()} />
        <MetricCard label="Estimated cost" value={`$${totalCost.toFixed(2)}`} hint="USD" />
      </section>

      {query.isLoading && <output className="cortex-admin-loading">Loading providers…</output>}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load providers.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}

      {providers.length > 0 && (
        <div className="cortex-admin-table-wrapper">
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">Status</th>
                <th scope="col">Tokens today</th>
                <th scope="col">Tokens this month</th>
                <th scope="col">Estimated cost</th>
                <th scope="col">Rate limit / min</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id}>
                  <td data-label="Provider">
                    <strong>{p.name}</strong>
                  </td>
                  <td data-label="Status">
                    <StatusPill
                      tone={
                        p.status === 'Operational'
                          ? 'success'
                          : p.status === 'Degraded'
                            ? 'warning'
                            : 'error'
                      }
                      label={p.status}
                      dot
                    />
                  </td>
                  <td data-label="Tokens today">{p.tokensToday.toLocaleString()}</td>
                  <td data-label="Tokens this month">{p.tokensThisMonth.toLocaleString()}</td>
                  <td data-label="Estimated cost">${p.estimatedCostUsd.toFixed(2)}</td>
                  <td data-label="Rate limit / min">{p.rateLimitPerMinute}</td>
                  <td data-label="Actions">
                    <div className="cortex-admin-stack">
                      <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
                        <Zap size={14} aria-hidden="true" />
                        <span>Ping</span>
                      </button>
                      <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
                        <Gauge size={14} aria-hidden="true" />
                        <span>Set limit</span>
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
