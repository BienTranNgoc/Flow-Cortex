import { Link } from 'react-router-dom';
import './WorkflowPages.css';
import { useWorkflowsQuery } from './useWorkflows';
import { useCapabilitiesQuery } from './useCapabilities';

/** `/workflows` — list = owner OR shared. Hides the Product Video entry when its feature flag is
 * off, since the start endpoint would 503 the request otherwise. */
const PRODUCT_VIDEO_WORKFLOW_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const productVideoVisible = capabilitiesQuery.data?.productVideoEnabled ?? true;

  return (
    <main>
      <h1>Workflows</h1>
      {workflowsQuery.isLoading && <output>Loading workflows…</output>}
      {workflowsQuery.isError && <p role="alert">Could not load workflows.</p>}
      {workflowsQuery.data && workflowsQuery.data.length > 0 && (
        <ul className="workflow-list">
          {workflowsQuery.data
            .filter((workflow) => productVideoVisible || workflow.id !== PRODUCT_VIDEO_WORKFLOW_ID)
            .map((workflow) => (
              <li key={workflow.id} className="workflow-card">
                <Link to={`/workflows/${workflow.id}`}>{workflow.name}</Link>
                {workflow.isShared && <span className="workflow-badge">Shared</span>}
              </li>
            ))}
          {workflowsQuery.data.filter((workflow) => productVideoVisible || workflow.id !== PRODUCT_VIDEO_WORKFLOW_ID)
            .length === 0 && <p>No workflows yet.</p>}
        </ul>
      )}
    </main>
  );
}
