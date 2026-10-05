import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';

export function HomePage() {
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  // Found by step type: a workflow's name and its place in the list belong to its owner.
  const mediaCheck = workflowsQuery.data?.find(
  (workflow) =>
    workflow.steps?.length === 2 &&
    workflow.steps[0]?.type === 'probe' &&
    workflow.steps[1]?.type === 'extract_audio',
);

  if (meQuery.isLoading) {
    return <output>Loading…</output>;
  }
  if (meQuery.isError || !meQuery.data) {
    return <p role="alert">Could not load your account.</p>;
  }

  return (
    <main>
      <h1>Welcome, {meQuery.data.name}</h1>
      <p>Signed in as {meQuery.data.email} ({meQuery.data.role}).</p>
      {mediaCheck?.id && (
        <p>
          <Link className="action-link" to={`/workflows/${mediaCheck.id}`}>
            Have a video or audio file? Check it →
          </Link>
        </p>
      )}
    </main>
  );
}
