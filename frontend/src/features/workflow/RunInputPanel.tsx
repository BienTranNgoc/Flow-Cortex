import type { RunInputResponse, RunResponse } from '@flow-cortex/api-client';
import type { RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { inputRows } from './runResultModel';
import { stepNameId, RUN_INPUT_KEY } from './stepDisplay';
import { TextResult } from './StepOutcome';
import './StepResult.css';
import { useRunInputsQuery } from './useWorkflows';

const UPLOAD_KINDS = ['video', 'audio', 'image'] as const;

/** An upload is a video or an audio file by its kind, and an image otherwise, as `RunInputs` draws it. */
function uploadKind(input: RunInputResponse): (typeof UPLOAD_KINDS)[number] {
  return input.kind === 'video' || input.kind === 'audio' ? input.kind : 'image';
}

interface RunInputPanelProps {
  id: string;
  panelRef: RefObject<HTMLElement>;
  run: RunResponse;
  runId: string;
}

/** The run's input: its file name, text or fields, and a count of the files uploaded for it. */
export function RunInputPanel({ id, panelRef, run, runId }: Readonly<RunInputPanelProps>) {
  const { t } = useTranslation();
  const uploads = useRunInputsQuery(runId).data;
  const rows = inputRows(run, uploads ? uploads.length : null);
  const counts = { video: 0, audio: 0, image: 0 };
  for (const upload of uploads ?? []) {
    counts[uploadKind(upload)] += 1;
  }
  const uploaded = UPLOAD_KINDS.filter((kind) => counts[kind] > 0).map((kind) =>
    t(`workflow.result.files.${kind}`, { count: counts[kind] }),
  );

  return (
    <section
      id={id}
      ref={panelRef}
      className="run-input-panel"
      aria-labelledby={stepNameId(RUN_INPUT_KEY)}
    >
      <h2 className="run-input-panel-title">{t('workflow.inputPanel.title')}</h2>
      {rows.kind === 'file' && (
        <p className="run-step-output">{t('workflow.inputPanel.file', { name: rows.name })}</p>
      )}
      {rows.kind === 'json' && (
        <dl className="run-input-rows">
          {rows.rows.map((row) => (
            <div key={row.key} className="output-field">
              <dt>{row.key}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {rows.kind === 'text' && (
        <TextResult text={rows.text} label={t('workflow.inputPanel.textRegion')} copy={false} />
      )}
      {rows.kind === 'empty' && (
        <p className="run-step-waiting">{t('workflow.inputPanel.empty')}</p>
      )}
      {uploaded.length > 0 && <p className="result-summary result-files">{uploaded.join(' · ')}</p>}
    </section>
  );
}
