import { useQuery } from '@tanstack/react-query';
import { Eye, EyeOff, Settings2, ShieldCheck, Wrench } from 'lucide-react';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchAdminWorkflows, type AdminWorkflow } from '../../app/mockAdminApi';

export function WorkflowsPage() {
  usePageTitle('Workflows & Templates');
  const query = useQuery({ queryKey: ['admin', 'workflows'], queryFn: fetchAdminWorkflows });
  const workflows = query.data ?? [];
  const total = workflows.length;
  const incomplete = workflows.filter((wf) => wf.status === 'Incomplete').length;
  const totalRuns = workflows.reduce((sum, wf) => sum + wf.runCount, 0);
  const visibleCount = workflows.filter((wf) => wf.visible).length;

  return (
    <div>
      <PageHeading
        eyebrow="Workflow Ops"
        title="Workflows & Templates"
        description="Manage the template catalogue shown on the user launchpad and the input limits each one enforces."
        actions={
          <button type="button" className="cortex-admin-btn cortex-admin-btn--primary">
            <Wrench size={16} aria-hidden="true" />
            <span>New template</span>
          </button>
        }
      />

      <section className="cortex-admin-metric-grid" aria-label="Workflow statistics">
        <MetricCard label="Templates" value={total} />
        <MetricCard
          label="Incomplete"
          value={incomplete}
          hint="Need validation before going live"
        />
        <MetricCard label="Visible on launchpad" value={visibleCount} />
        <MetricCard label="Total runs" value={totalRuns} />
      </section>

      {query.isLoading && <output className="cortex-admin-loading">Loading workflows…</output>}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load workflows.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}

      {workflows.length > 0 && (
        <div className="cortex-admin-table-wrapper">
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">Template</th>
                <th scope="col">Status</th>
                <th scope="col">Input limits</th>
                <th scope="col">Magic bytes</th>
                <th scope="col">Runs</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {workflows.map((wf) => (
                <WorkflowRow key={wf.id} workflow={wf} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function WorkflowRow({ workflow }: Readonly<{ workflow: AdminWorkflow }>) {
  return (
    <tr>
      <td data-label="Template">
        <strong>{workflow.name}</strong>
        <p className="cortex-admin-page-description">{workflow.description}</p>
      </td>
      <td data-label="Status">
        <StatusPill
          tone={workflow.status === 'Complete' ? 'success' : 'warning'}
          label={workflow.status}
        />
      </td>
      <td data-label="Input limits">
        <div className="cortex-admin-stack">
          <span>Video: {workflow.videoLimitMB} MB</span>
          <span>Audio: {workflow.audioLimitMB} MB</span>
          <span>Image: {workflow.imageLimitMB} MB</span>
        </div>
        <p className="cortex-admin-stale">
          {workflow.allowedExtensions.join(' · ')}
        </p>
      </td>
      <td data-label="Magic bytes">
        {workflow.magicBytesEnforced ? (
          <StatusPill tone="info" label="Strict" dot />
        ) : (
          <StatusPill tone="muted" label="Relaxed" />
        )}
        {!workflow.magicBytesEnforced && (
          <p className="cortex-admin-stale">Configure to block faked extensions</p>
        )}
      </td>
      <td data-label="Runs">{workflow.runCount.toLocaleString()}</td>
      <td data-label="Actions">
        <div className="cortex-admin-stack">
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
            <Settings2 size={14} aria-hidden="true" />
            <span>Edit</span>
          </button>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
            {workflow.visible ? <EyeOff size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}
            <span>{workflow.visible ? 'Hide' : 'Show'}</span>
          </button>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
            <ShieldCheck size={14} aria-hidden="true" />
            <span>Validate</span>
          </button>
        </div>
      </td>
    </tr>
  );
}
