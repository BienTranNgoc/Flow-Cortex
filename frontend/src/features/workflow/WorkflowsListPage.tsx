import { Link } from 'react-router-dom';
import { useMeQuery } from '../auth/useAuth';
import './WorkflowPages.css';
import { useWorkflowsQuery } from './useWorkflows';

/** `/workflows` — list = owner OR shared. */
export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();

  return (
    <main className="workflows">
      <h1 className="visually-hidden">Workflows</h1>
      <section className="workflows-list-section" aria-labelledby="workflows-list-title">
        <h2 id="workflows-list-title">Your workflows</h2>
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
      </section>

      {meQuery.data?.name && (
        <p className="workflows-footer" aria-label="Signed-in user">
          Welcome, {meQuery.data.name}
        </p>
      )}
    </main>
  );
}
