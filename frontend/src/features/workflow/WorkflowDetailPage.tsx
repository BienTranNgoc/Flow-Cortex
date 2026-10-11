import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import './WorkflowPages.css';
import { MediaStartForm } from './MediaStartForm';
import { ProductVideoStartForm } from './ProductVideoStartForm';
import { startRunSchema, type StartRunFormValues } from './startRunSchema';
import { useStartRunMutation, useWorkflowQuery } from './useWorkflows';

const PRODUCT_VIDEO_WORKFLOW_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

/** Start form of a workflow whose first step runs inline: an optional text input. */
function InlineStartForm({
  workflowId,
  disabled,
}: Readonly<{ workflowId: string; disabled: boolean }>) {
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
      <button type="submit" disabled={startRunMutation.isPending || disabled}>
        {startRunMutation.isPending ? 'Starting…' : 'Start run'}
      </button>
    </form>
  );
}

/**
 * `/workflows/:id` — steps + "start run". On a successful start, navigates straight to the
 * run-progress view so watching it progress is the very next turn. The form follows the first
 * step: one that runs remotely works on an uploaded file, an inline one takes optional text.
 *
 * Renders the form shell as soon as the workflow id resolves, even while the workflow fetch is
 * still in flight or has errored: a first-visit user expects to see something they can start
 * filling in, not a transient `Loading…` or a bare `Could not load this workflow.` that hides
 * the entire page. The form is disabled until the workflow definition arrives; an error keeps
 * the form visible so the user can retry, and the alert sits inline above it.
 */
export function WorkflowDetailPage() {
  const { id } = useParams<{ id: string }>();

  if (!id) {
    return <p role="alert">No workflow selected.</p>;
  }
  return <WorkflowDetailView workflowId={id} />;
}

function WorkflowDetailView({ workflowId }: Readonly<{ workflowId: string }>) {
  const workflowQuery = useWorkflowQuery(workflowId);
  const workflow = workflowQuery.data;
  // Match the Product Video workflow from the URL, not from the loaded data: the data fetch
  // races the first paint, and matching from `workflow.id` only kicks in once the fetch
  // settles, which briefly renders the wrong start form (4 steps + plain input) before
  // switching to the 5-field product form.
  const isProductVideo = workflowId === PRODUCT_VIDEO_WORKFLOW_ID;
  const startsWithUpload = workflow?.steps?.[0]?.mode === 'remote';
  // Keep the form rendered and editable from the first paint — a first-visit user expects to
  // start filling in, not stare at a blank page. The Start button stays disabled while the
  // workflow is loading (no definition to dispatch to) or has errored (the mutation would
  // 404 against an unknown workflow id).
  const startDisabled = workflowQuery.isLoading || workflowQuery.isError || !workflow;

  return (
    <main>
      <h1>{workflow?.name ?? 'Workflow'}</h1>
      {workflow?.isShared && <p>Shared with every member.</p>}
      {workflowQuery.isError && (
        <p role="alert">Could not load this workflow. Try again, or pick another one.</p>
      )}
      {workflowQuery.isLoading && <output>Loading workflow…</output>}
      {workflow?.steps && workflow.steps.length > 0 && (
        <ol className="workflow-steps">
          {workflow.steps.map((step) => (
            <li key={step.id}>{step.name ?? `Step ${step.stepOrder}`}</li>
          ))}
        </ol>
      )}
      {!workflow || isProductVideo ? (
        <ProductVideoStartForm disabled={startDisabled} />
      ) : startsWithUpload ? (
        <MediaStartForm workflowId={workflowId} disabled={startDisabled} />
      ) : (
        <InlineStartForm workflowId={workflowId} disabled={startDisabled} />
      )}
    </main>
  );
}
