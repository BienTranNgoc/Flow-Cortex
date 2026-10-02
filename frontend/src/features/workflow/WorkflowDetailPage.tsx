import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import './WorkflowPages.css';
import { MediaStartForm } from './MediaStartForm';
import { startRunSchema, type StartRunFormValues } from './startRunSchema';
import { useStartRunMutation, useWorkflowQuery } from './useWorkflows';

/** Start form of a workflow whose first step runs inline: an optional text input. */
function InlineStartForm({ workflowId }: Readonly<{ workflowId: string }>) {
  const navigate = useNavigate();
  const startRunMutation = useStartRunMutation(workflowId);
  const { register, handleSubmit } = useForm<StartRunFormValues>({
    resolver: zodResolver(startRunSchema),
    defaultValues: { input: '' },
  });

  const startError =
    startRunMutation.error instanceof ApiError ? startRunMutation.error.problem : undefined;
  // Execution-disabled (kill switch off) is a 503 — show the reason plus a concrete next action
  // rather than a bare error or a spinner that never resolves.
  const isExecutionDisabled =
    startRunMutation.error instanceof ApiError && startRunMutation.error.status === 503;

  const onStart = handleSubmit((values) => {
    const trimmedInput = values.input?.trim();
    startRunMutation.mutate(trimmedInput ? { input: trimmedInput } : {}, {
      onSuccess: (run) => {
        if (run.id) {
          navigate(`/workflows/${workflowId}/runs/${run.id}`);
        }
      },
    });
  });

  return (
    <form onSubmit={onStart} noValidate aria-label="Start run">
      <div className="field">
        <label htmlFor="run-input">Input (optional)</label>
        <input id="run-input" {...register('input')} />
      </div>
      {startError && (
        <div role="alert">
          <p>
            {startError.detail ??
              (isExecutionDisabled
                ? 'Workflow execution is currently disabled.'
                : 'Could not start the run.')}
          </p>
          <p>Try again later, or go back to the Workflows list.</p>
        </div>
      )}
      <button type="submit" disabled={startRunMutation.isPending}>
        {startRunMutation.isPending ? 'Starting…' : 'Start run'}
      </button>
    </form>
  );
}

/**
 * `/workflows/:id` — steps + "start run". On a successful start, navigates straight to the
 * run-progress view so watching it progress is the very next turn. The form follows the first
 * step: one that runs remotely works on an uploaded file, an inline one takes optional text.
 */
export function WorkflowDetailPage() {
  const { id } = useParams<{ id: string }>();
  const workflowQuery = useWorkflowQuery(id ?? '');

  if (!id) {
    return <p role="alert">No workflow selected.</p>;
  }
  if (workflowQuery.isLoading) {
    return <output>Loading workflow…</output>;
  }
  if (workflowQuery.isError || !workflowQuery.data) {
    return <p role="alert">Could not load this workflow.</p>;
  }

  const workflow = workflowQuery.data;
  const startsWithUpload = workflow.steps?.[0]?.mode === 'remote';

  return (
    <main>
      <h1>{workflow.name}</h1>
      {workflow.isShared && <p>Shared with every member.</p>}
      {workflow.steps && workflow.steps.length > 0 && (
        <ol className="workflow-steps">
          {workflow.steps.map((step) => (
            <li key={step.id}>{step.name ?? `Step ${step.stepOrder}`}</li>
          ))}
        </ol>
      )}
      {startsWithUpload ? <MediaStartForm workflowId={id} /> : <InlineStartForm workflowId={id} />}
    </main>
  );
}
