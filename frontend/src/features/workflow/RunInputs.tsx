import type { RunInputResponse } from '@flow-cortex/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRunInputsQuery } from './useWorkflows';

interface RunInputsProps {
  runId: string;
}

interface InputItemProps {
  input: RunInputResponse;
  imageNumber: number;
}

function InputItem({ input, imageNumber }: Readonly<InputItemProps>) {
  const { t } = useTranslation();
  const [gone, setGone] = useState(false);

  if (gone) {
    return <p className="run-input-gone">{t('workflow.inputs.expired')}</p>;
  }
  const onError = () => setGone(true);
  if (input.kind === 'video') {
    return (
      <video
        className="run-input-media run-input-video"
        controls
        preload="metadata"
        src={input.url}
        onError={onError}
      />
    );
  }
  if (input.kind === 'audio') {
    return (
      <audio
        className="run-input-media run-input-audio"
        controls
        src={input.url}
        onError={onError}
      />
    );
  }
  return (
    <img
      className="run-input-media run-input-image"
      loading="lazy"
      src={input.url}
      alt={t('workflow.inputs.imageAlt', { number: imageNumber })}
      onError={onError}
    />
  );
}

/** The files the member uploaded for this run, shown above the pipeline. */
export function RunInputs({ runId }: Readonly<RunInputsProps>) {
  const { t } = useTranslation();
  const headingId = useId();
  const query = useRunInputsQuery(runId);

  if (query.isError) {
    return (
      <p role="alert" className="run-inputs-status run-inputs-error">
        {t('workflow.inputs.loadError')}
      </p>
    );
  }
  if (query.isPending) {
    return <output className="run-inputs-status">{t('workflow.inputs.loading')}</output>;
  }
  const inputs = query.data;
  if (inputs.length === 0) {
    return null;
  }

  let imageNumber = 0;
  return (
    <section className="run-inputs" aria-labelledby={headingId}>
      <h2 id={headingId} className="run-inputs-heading">
        {t('workflow.inputs.heading')}
      </h2>
      <ul className="run-inputs-grid">
        {inputs.map((input) => {
          if (input.kind !== 'video' && input.kind !== 'audio') {
            imageNumber += 1;
          }
          return (
            <li
              key={input.bindingKey}
              className={`run-input run-input-${input.kind === 'audio' ? 'audio' : 'visual'}`}
            >
              <InputItem input={input} imageNumber={imageNumber} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}
