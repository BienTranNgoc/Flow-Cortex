import type { RunStepOutputResponse } from '@flow-cortex/api-client';
import { openInCurrentTab, saveTextFile } from './browser';
import { outputFields, parseReport } from './stepOutputFields';
import { summaryText } from './summaryText';
import { useOutputUrlMutation } from './useWorkflows';

interface StepDownloadsProps {
  runId: string;
  stepOrder: number;
  output: string | undefined;
  details: RunStepOutputResponse;
  fileName: string | undefined;
}

/**
 * The two downloads of a finished step, summary first: a text file built here from the labelled
 * fields, then the stored JSON report through a fresh presigned URL opened in this tab, so the
 * browser saves it and the page stays.
 */
export function StepDownloads({ runId, stepOrder, output, details, fileName }: StepDownloadsProps) {
  const outputUrl = useOutputUrlMutation(runId, stepOrder, details.name);
  const report = output === undefined ? null : parseReport(output);

  const saveSummary = () => {
    saveTextFile('video-summary.txt', summaryText(fileName, report ? outputFields(report) : []));
  };

  // Busy rather than disabled, so focus stays on the button while the link is fetched.
  const openDetails = () => {
    if (outputUrl.isPending) {
      return;
    }
    outputUrl.mutate(undefined, { onSuccess: ({ url }) => openInCurrentTab(url) });
  };

  return (
    <div className="run-step-downloads">
      <button type="button" onClick={saveSummary}>
        Download summary (.txt)
      </button>
      <button
        type="button"
        onClick={openDetails}
        aria-disabled={outputUrl.isPending}
        aria-busy={outputUrl.isPending}
      >
        {outputUrl.isPending ? 'Getting the file…' : 'Download technical details (JSON file)'}
      </button>
      {outputUrl.isError && (
        <p role="alert" className="run-step-download-error">
          Could not get download link
        </p>
      )}
    </div>
  );
}
