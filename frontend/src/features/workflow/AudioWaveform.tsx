import { useTranslation } from 'react-i18next';
import { useOutputPeaksQuery } from './useWorkflows';

interface AudioWaveformProps {
  runId: string;
  stepOrder: number;
  name: string;
}

const HEIGHT = 40;
const BAR_FILL = 0.7;
// A silent slot still draws a hairline, so the card keeps its shape.
const MIN_BAR = 0.04;

/**
 * The sound of one stored output as bars. A placeholder of the same size shows while the peaks
 * load; when the server cannot decode the sound or answers an error nothing is drawn, and the
 * download beside it stays the member's way to the file.
 */
export function AudioWaveform({ runId, stepOrder, name }: Readonly<AudioWaveformProps>) {
  const { t } = useTranslation();
  const query = useOutputPeaksQuery(runId, stepOrder, name, true);

  if (query.isPending) {
    return <div className="audio-waveform audio-waveform-skeleton" aria-hidden="true" />;
  }
  const peaks = query.data?.peaks;
  if (query.isError || !peaks || peaks.length === 0) {
    return null;
  }

  return (
    <svg
      className="audio-waveform"
      viewBox={`0 0 ${peaks.length} ${HEIGHT}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={t('workflow.waveform.name', { label: name })}
    >
      {peaks.map((peak, index) => {
        const height = Math.max(Math.min(Math.max(peak, 0), 1), MIN_BAR) * HEIGHT;
        return (
          <rect
            key={index}
            x={index + (1 - BAR_FILL) / 2}
            y={(HEIGHT - height) / 2}
            width={BAR_FILL}
            height={height}
            rx={BAR_FILL / 2}
          />
        );
      })}
    </svg>
  );
}
