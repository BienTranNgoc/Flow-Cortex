import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../auth/useAuth';
import './WorkflowPages.css';
import { useWorkflowsQuery } from './useWorkflows';
import { useCapabilitiesQuery } from './useCapabilities';

/** `/workflows` — list = owner OR shared. Hides the Product Video entry when its feature flag is
 * off, since the start endpoint would 503 the request otherwise. */
export function WorkflowsListPage() {
  const { t } = useTranslation();
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const capabilities = capabilitiesQuery.data;
  // The list waits for the capabilities: before they arrive, the Product Video entry cannot be
  // told apart, and showing it would flash an entry the flag then removes. If they fail, nothing
  // is hidden; the start endpoint still refuses a switched-off flow.
  // Filtered once, so the list and the "No workflows yet" count below cannot disagree: counting the
  // unfiltered array would claim emptiness for a list hidden only by the flag.
  const visible = capabilitiesQuery.isPending
    ? undefined
    : workflowsQuery.data?.filter(
        (workflow) =>
          capabilities?.productVideoEnabled !== false ||
          workflow.id !== capabilities.productVideoWorkflowId,
      );

  return (
    <main className="workflows">
      <h1 className="visually-hidden">{t('workflow.list.heading')}</h1>
      <section className="workflows-list-section" aria-labelledby="workflows-list-title">
        <h2 id="workflows-list-title">{t('workflow.list.yours')}</h2>
        {(workflowsQuery.isLoading || (capabilitiesQuery.isPending && !workflowsQuery.isError)) && (
          <output>{t('workflow.list.loading')}</output>
        )}
        {workflowsQuery.isError && <p role="alert">{t('workflow.list.loadError')}</p>}
        {visible?.length === 0 && <p>{t('workflow.list.empty')}</p>}
        {visible && visible.length > 0 && (
          <ul className="workflow-list">
            {visible.map((workflow) => (
              <li key={workflow.id} className="workflow-card">
                <Link to={`/workflows/${workflow.id}`}>{workflow.name}</Link>
                {workflow.isShared && (
                  <span className="workflow-badge">{t('workflow.list.shared')}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {meQuery.data?.name && (
        <p className="workflows-footer">{t('workflow.list.welcome', { name: meQuery.data.name })}</p>
      )}
    </main>
  );
}