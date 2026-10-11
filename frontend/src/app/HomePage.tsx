import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError, type WorkflowResponse } from '@flow-cortex/api-client';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { launchpadSchema, type LaunchpadFormValues } from '../features/workflow/launchpadSchema';
import { useStartRunMutation, useWorkflowsQuery } from '../features/workflow/useWorkflows';
import { useTransitionNavigate } from './useTransitionNavigate';

/** A workflow whose first step runs on the media engine needs an uploaded file, not a link. */
function isUploadWorkflow(workflow: WorkflowResponse) {
  return workflow.steps?.[0]?.mode === 'remote';
}

export function HomePage() {
  const { go } = useTransitionNavigate();
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  const uploadWorkflow = workflowsQuery.data?.find(isUploadWorkflow);
  const workflow = workflowsQuery.data?.find((candidate) => !isUploadWorkflow(candidate));
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
          if (run.id) go(`/runs/${run.id}`);
        },
      },
    );
  });
  const serverError =
    startRunMutation.error instanceof ApiError ? startRunMutation.error.problem?.detail : undefined;

  return (
    <main className="launchpad">
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
        <p className="eyebrow">1 · SOURCE INPUT</p>
        <h2>Drop a TikTok or Douyin link</h2>
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
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button
            className="primary-button launch-button"
            type="submit"
            disabled={startRunMutation.isPending || !workflow}
            aria-busy={startRunMutation.isPending}
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
