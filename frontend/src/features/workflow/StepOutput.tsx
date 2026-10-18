import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { outputFields, parseReport } from './stepOutputFields';

/** A step's output: a JSON object as labelled fields with its raw JSON behind a toggle, anything else as text. */
export function StepOutput({ output }: Readonly<{ output: string }>) {
  const { t } = useTranslation();
  const [showRaw, setShowRaw] = useState(false);
  const rawId = useId();
  const report = parseReport(output);

  if (!report) {
    return <p className="run-step-output">{output}</p>;
  }

  return (
    <div className="run-step-output">
      <dl className="output-fields">
        {outputFields(report, t, activeLocale()).map((field) => (
          <div key={field.label} className="output-field">
            <dt>{field.label}</dt>
            <dd>{field.value}</dd>
          </div>
        ))}
      </dl>
      <button
        type="button"
        className="link-button"
        aria-expanded={showRaw}
        aria-controls={rawId}
        onClick={() => setShowRaw((shown) => !shown)}
      >
        {showRaw ? t('workflow.output.hideRaw') : t('workflow.output.showRaw')}
      </button>
      {showRaw && (
        <pre id={rawId} className="output-raw">
          {JSON.stringify(report, null, 2)}
        </pre>
      )}
    </div>
  );
}
