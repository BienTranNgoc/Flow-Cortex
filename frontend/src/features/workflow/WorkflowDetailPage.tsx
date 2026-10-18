import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useParams } from 'react-router-dom';
import { ApiError, type StartRunRequest, type WorkflowStepResponse } from '@flow-cortex/api-client';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { CreditsRefusal } from '../credits/CreditsRefusal';
import { formatCredits } from '../credits/formatCredits';
import { invalidateBalance } from '../credits/useBalance';
import './WorkflowPages.css';
import { MediaStartForm } from './MediaStartForm';
import { Pipeline } from './Pipeline';
import { ProductVideoStartForm } from './ProductVideoStartForm';
import { stepKey } from './stepDisplay';
import { startProblem, type IncompleteStep, type IncompleteWorkflow } from './startProblem';
import { startRunSchema, type StartRunFormValues } from './startRunSchema';
import { useCapabilitiesQuery } from './useCapabilities';
import { useStartRunMutation, useWorkflowQuery } from './useWorkflows';

/** Start form of a workflow whose first step runs inline: an optional text input. */
function InlineStartForm({
  workflowId,
  disabled,
  onIncomplete,
}: Readonly<{
  workflowId: string;
  disabled: boolean;
  onIncomplete: (problem: IncompleteWorkflow | null) => void;
}>) {
  const { go } = useTransitionNavigate();
  const queryClient = useQueryClient();
  const startRunMutation = useStartRunMutation(workflowId);
  // The refused start, so "Run anyway" resends exactly what the member submitted.
  const lastBody = useRef<StartRunRequest>({});
  const { register, handleSubmit } = useForm<StartRunFormValues>({
    resolver: zodResolver(startRunSchema),
    defaultValues: { input: '' },
  });

  // Execution-disabled (kill switch off) is a 503 — show the reason plus a concrete next action
  // rather than a bare error or a spinner that never resolves.
  const isExecutionDisabled =
    startRunMutation.error instanceof ApiError && startRunMutation.error.status === 503;
  const problem = startRunMutation.error
    ? startProblem(
        startRunMutation.error,
        isExecutionDisabled ? 'Workflow execution is currently disabled.' : undefined,
      )
    : null;
  const alertMessage =
    problem?.kind === 'field' || problem?.kind === 'alert' ? problem.message : null;

  const start = (body: StartRunRequest) => {
    if (startRunMutation.isPending) {
      return;
    }
    onIncomplete(null);
    lastBody.current = body;
    startRunMutation.mutate(body, {
      onSuccess: (run) => {
        void invalidateBalance(queryClient);
        if (run.id) {
          go(`/workflows/${workflowId}/runs/${run.id}`);
        }
      },
      onError: (error) => {
        const refused = startProblem(error);
        if (refused.kind === 'incomplete') {
          onIncomplete(refused);
        }
      },
    });
  };

  const onStart = handleSubmit((values) => {
    const trimmedInput = values.input?.trim();
    start(trimmedInput ? { input: trimmedInput } : {});
  });

  return (
    <form onSubmit={onStart} noValidate aria-label="Start run">
      <div className="field">
        <label htmlFor="run-input">Input (optional)</label>
        <input id="run-input" {...register('input')} />
      </div>
      {alertMessage && (
        <div role="alert">
          <p>{alertMessage}</p>
          <p>Try again later, or go back to the Workflows list.</p>
        </div>
      )}
      {problem?.kind === 'credits' && (
        <CreditsRefusal
          message={problem.message}
          busy={startRunMutation.isPending}
          onRunAnyway={
            problem.canRunAnyway
              ? () => start({ ...lastBody.current, acknowledgeCost: true })
              : undefined
          }
        />
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
  return <WorkflowDetailView key={id} workflowId={id} />;
}

/** "Step 2 · Echo step 2: reason", or "Step 2: reason" when the workflow has no such named step. */
function incompleteLine(
  step: IncompleteStep,
  steps: readonly WorkflowStepResponse[] | undefined,
): string {
  const name = steps?.find((candidate) => candidate.stepOrder === step.order)?.name;
  return `Step ${step.order}${name ? ` · ${name}` : ''}: ${step.reason}`;
}

function WorkflowDetailView({ workflowId }: Readonly<{ workflowId: string }>) {
  const workflowQuery = useWorkflowQuery(workflowId);
  const capabilitiesQuery = useCapabilitiesQuery();
  const workflow = workflowQuery.data;
  const [incomplete, setIncomplete] = useState<IncompleteWorkflow | null>(null);
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
    startForm = <ProductVideoStartForm disabled={startDisabled} onIncomplete={setIncomplete} />;
  } else if (workflow && !capabilitiesQuery.isPending) {
    startForm = startsWithUpload ? (
      <MediaStartForm
        workflowId={workflowId}
        disabled={startDisabled}
        onIncomplete={setIncomplete}
      />
    ) : (
      <InlineStartForm
        workflowId={workflowId}
        disabled={startDisabled}
        onIncomplete={setIncomplete}
      />
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
        <Pipeline
          steps={workflow.steps.map((step) => ({
            id: stepKey(step),
            name: step.name ?? `Step ${step.stepOrder}`,
            order: step.stepOrder,
          }))}
          problemOrder={incomplete?.firstOrder ?? undefined}
        />
      )}
      {incomplete && (
        <div role="alert" className="form-error">
          <p>{incomplete.summary}</p>
          {incomplete.steps.length > 0 && (
            <ul>
              {incomplete.steps.map((step) => (
                <li key={step.order}>{incompleteLine(step, workflow?.steps)}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {startForm}
      {startForm && typeof workflow?.estimatedCredits === 'number' && (
        <p className="field-hint">
          Estimated cost: {formatCredits(workflow.estimatedCredits)} credits
        </p>
      )}
    </main>
  );
}
