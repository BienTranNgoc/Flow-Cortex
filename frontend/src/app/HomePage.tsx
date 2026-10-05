import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError, type WorkflowResponse } from '@flow-cortex/api-client';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { launchpadSchema, type LaunchpadFormValues } from '../features/workflow/launchpadSchema';
import { useStartRunMutation, useWorkflowsQuery } from '../features/workflow/useWorkflows';

const URL_WORKFLOW_NAME = /\b(?:url|link|tiktok|douyin)\b/i;

function isUploadWorkflow(workflow: WorkflowResponse) {
  return (
    workflow.steps?.[0]?.mode === 'remote' &&
    workflow.steps?.[0]?.type === 'probe' &&
    workflow.steps?.[1]?.type === 'extract_audio'
  );
}

function selectUrlWorkflow(workflows: WorkflowResponse[]) {
  return (
    workflows.find(
      (workflow) => URL_WORKFLOW_NAME.test(workflow.name ?? '') && !isUploadWorkflow(workflow),
    ) ?? workflows.find((workflow) => !isUploadWorkflow(workflow))
  );
}

export function HomePage() {
  const navigate = useNavigate();
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  const uploadWorkflow = workflowsQuery.data?.find(isUploadWorkflow);
  const workflow = workflowsQuery.data ? selectUrlWorkflow(workflowsQuery.data) : undefined;
  const startRunMutation = useStartRunMutation(workflow?.id ?? '');
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LaunchpadFormValues>({ resolver: zodResolver(launchpadSchema) });

  if (meQuery.isLoading) return <output>Loading…</output>;
  if (meQuery.isError || !meQuery.data) {
    return <p role="alert">Could not load your account.</p>;
  }

  const onSubmit = handleSubmit(({ sourceUrl }) => {
    if (!workflow?.id) return;
    startRunMutation.mutate(
      { input: sourceUrl.trim() },
      {
        onSuccess: (run) => {
          if (run.id) navigate(`/runs/${run.id}`);
        },
      },
    );
  });
  const serverError =
    startRunMutation.error instanceof ApiError ? startRunMutation.error.problem?.detail : undefined;

  return (
    <main className="launchpad">
      <h2 className="visually-hidden">Welcome, {meQuery.data.name}</h2>
      <section className="launchpad-hero">
        <p className="eyebrow">CREATOR LAUNCHPAD · READY TO CREATE</p>
        <h1>
          Turn any link or prompt into AI media <span>pipeline</span>
        </h1>
        <p>
          Automate scripting, voiceover, visuals and video composition in seconds with your
          production-ready workflows.
        </p>
      </section>
      <section className="launchpad-card">
        <div className="launchpad-card-header">
          <div>
            <p className="eyebrow">1 · SOURCE INPUT</p>
            <h2>Drop a TikTok or Douyin link</h2>
          </div>
          <span className="status-pill">● Pipeline online</span>
        </div>
        <form onSubmit={onSubmit} noValidate aria-label="Start media pipeline">
          <div className="field">
            <label htmlFor="source-url">Source URL</label>
            <input
              id="source-url"
              placeholder="https://www.tiktok.com/@creator/video/..."
              {...register('sourceUrl')}
            />
            {errors.sourceUrl && <p role="alert">{errors.sourceUrl.message}</p>}
          </div>
          <div className="preset-grid">
            <label className="preset-control">
              <span>Voice AI</span>
              <select defaultValue="natural">
                <option value="natural">Natural · Nova</option>
                <option value="studio">Studio · Atlas</option>
                <option value="warm">Warm · Aura</option>
              </select>
            </label>
            <label className="preset-control">
              <span>Aspect ratio</span>
              <select defaultValue="vertical">
                <option value="vertical">9:16 · Vertical</option>
                <option value="square">1:1 · Square</option>
                <option value="wide">16:9 · Wide</option>
              </select>
            </label>
            <label className="toggle-control">
              <input type="checkbox" defaultChecked />
              <span>Human review</span>
              <small>Quality gate before delivery</small>
            </label>
          </div>
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button
            className="primary-button launch-button"
            type="submit"
            disabled={startRunMutation.isPending || !workflow}
          >
            {startRunMutation.isPending ? 'Starting pipeline…' : 'Generate pipeline →'}
          </button>
          {!workflow && !workflowsQuery.isLoading && (
            <p role="alert">No workflow is available for this account yet.</p>
          )}
        </form>
      </section>
      <section className="launchpad-footer">
        <p>Welcome, {meQuery.data.name}</p>
        {uploadWorkflow?.id && (
          <Link className="action-link" to={`/workflows/${uploadWorkflow.id}`}>
            Have a video or audio file? Check it →
          </Link>
        )}
      </section>
    </main>
  );
}
