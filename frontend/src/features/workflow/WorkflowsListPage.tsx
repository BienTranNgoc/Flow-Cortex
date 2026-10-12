import { Link } from 'react-router-dom';
import { useMeQuery } from '../auth/useAuth';
import './WorkflowPages.css';
import { useWorkflowsQuery } from './useWorkflows';
import { useCapabilitiesQuery } from './useCapabilities';

/** `/workflows` — list = owner OR shared. Hides the Product Video entry when its feature flag is
 * off, since the start endpoint would 503 the request otherwise. */
const PRODUCT_VIDEO_WORKFLOW_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const productVideoVisible = capabilitiesQuery.data?.productVideoEnabled ?? true;
  // Filtered once, so the list and the "No workflows yet" count below cannot disagree: counting the
  // unfiltered array would claim emptiness for a list hidden only by the flag.
  const visible = workflowsQuery.data?.filter(
    (workflow) => productVideoVisible || workflow.id !== PRODUCT_VIDEO_WORKFLOW_ID,
  );

  return (
    <main className="workflows">
      <h1 className="visually-hidden">Workflows</h1>
      <section className="workflows-list-section" aria-labelledby="workflows-list-title">
        <h2 id="workflows-list-title">Your workflows</h2>
        {workflowsQuery.isLoading && <output>Loading workflows…</output>}
        {workflowsQuery.isError && <p role="alert">Could not load workflows.</p>}
        {visible?.length === 0 && <p>No workflows yet.</p>}
        {visible && visible.length > 0 && (
          <ul className="workflow-list">
            {visible.map((workflow) => (
              <li key={workflow.id} className="workflow-card">
                <Link to={`/workflows/${workflow.id}`}>{workflow.name}</Link>
                {workflow.isShared && <span className="workflow-badge">Shared</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {meQuery.data?.name && <p className="workflows-footer">Welcome, {meQuery.data.name}</p>}
    </main>
  );
}