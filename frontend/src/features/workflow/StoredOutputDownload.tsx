import type { RunStepOutputResponse } from '@flow-cortex/api-client';
import { openInCurrentTab } from './browser';
import { useOutputUrlMutation } from './useWorkflows';

interface StoredOutputDownloadProps {
  runId: string;
  stepOrder: number;
  output: RunStepOutputResponse;
}

function label(output: RunStepOutputResponse): string {
  if (output.mime === 'audio/wav') {
    return 'Download extracted audio (WAV)';
  }
  return `Download ${output.name}`;
}

export function StoredOutputDownload({
  runId,
  stepOrder,
  output,
}: Readonly<StoredOutputDownloadProps>) {
  const outputUrl = useOutputUrlMutation(runId, stepOrder, output.name);

  const download = () => {
    if (outputUrl.isPending) {
      return;
    }
    outputUrl.mutate(undefined, { onSuccess: ({ url }) => openInCurrentTab(url) });
  };

  return (
    <div className="run-step-downloads">
      <button
        type="button"
        onClick={download}
        aria-disabled={outputUrl.isPending}
        aria-busy={outputUrl.isPending}
      >
        {outputUrl.isPending ? 'Getting the file…' : label(output)}
      </button>
      {outputUrl.isError && (
        <p role="alert" className="run-step-download-error">
          Could not get download link
        </p>
      )}
    </div>
  );
}
