import { Link } from 'react-router-dom';
import './WorkflowPages.css';
import { useWorkflowsQuery } from './useWorkflows';

/** `/workflows` — list = owner OR shared. */
export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();

  return (
    <main>
      <h1>Workflows</h1>
      {workflowsQuery.isLoading && <output>Loading workflows…</output>}
      {workflowsQuery.isError && <p role="alert">Could not load workflows.</p>}
      {workflowsQuery.data?.length === 0 && <p>No workflows yet.</p>}
      {workflowsQuery.data && workflowsQuery.data.length > 0 && (
        <ul className="workflow-list">
          {workflowsQuery.data.map((workflow) => (
            <li key={workflow.id} className="workflow-card">
              <Link to={`/workflows/${workflow.id}`}>{workflow.name}</Link>
              {workflow.isShared && <span className="workflow-badge">Shared</span>}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
