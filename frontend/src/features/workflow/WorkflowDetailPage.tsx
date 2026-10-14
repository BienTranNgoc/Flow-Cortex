import { zodResolver } from '@hookform/resolvers/zod';
import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import './WorkflowPages.css';
import { MediaStartForm } from './MediaStartForm';
import { ProductVideoStartForm } from './ProductVideoStartForm';
import { startRunSchema, type StartRunFormValues } from './startRunSchema';
import { useCapabilitiesQuery } from './useCapabilities';
import { useStartRunMutation, useWorkflowQuery } from './useWorkflows';

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
 * The Product Video form renders as soon as the capabilities identify it, even while the workflow
 * fetch is in flight or has errored, disabled until the definition arrives. Any other form waits
 * for the workflow and for the capabilities to settle; a capabilities error does not block it.
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
  const capabilitiesQuery = useCapabilitiesQuery();
  const workflow = workflowQuery.data;
  // Until the capabilities settle, no form renders: guessing would briefly show the generic form
  // (4 steps + plain input) before switching to the 5-field product form. If they fail, the
  // generic form is safe — the server refuses a Product Video start through the generic route.
  const productVideoWorkflowId = capabilitiesQuery.data?.productVideoWorkflowId;
  const isProductVideo =
    productVideoWorkflowId !== undefined && workflowId === productVideoWorkflowId;
  const startsWithUpload = workflow?.steps?.[0]?.mode === 'remote';
  // A start against a definition that is loading or failed to load would 404.
  const startDisabled = workflowQuery.isLoading || workflowQuery.isError || !workflow;
  let startForm: ReactNode = null;
  if (isProductVideo) {
    startForm = <ProductVideoStartForm disabled={startDisabled} />;
  } else if (workflow && !capabilitiesQuery.isPending) {
    startForm = startsWithUpload ? (
      <MediaStartForm workflowId={workflowId} disabled={startDisabled} />
    ) : (
      <InlineStartForm workflowId={workflowId} disabled={startDisabled} />
    );
  }

  return (
    <main>
      <h1>{workflow?.name ?? 'Workflow'}</h1>
      {workflow?.isShared && <p>Shared with every member.</p>}
      {workflowQuery.isError && (
        <p role="alert">Could not load this workflow. Try again, or pick another one.</p>
      )}
      {(workflowQuery.isLoading || capabilitiesQuery.isPending) && (
        <output>Loading workflow…</output>
      )}
      {workflow?.steps && workflow.steps.length > 0 && (
        <ol className="workflow-steps">
          {workflow.steps.map((step) => (
            <li key={step.id}>{step.name ?? `Step ${step.stepOrder}`}</li>
          ))}
        </ol>
      )}
      {startForm}
    </main>
  );
}
