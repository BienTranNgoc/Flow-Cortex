import { useTranslation } from 'react-i18next';
import { SPLIT_MAX, SPLIT_MIN } from './runCanvasModel';
import type { RunSplitHandleBinding } from './useRunSplit';

/** The draggable, keyboard-operable boundary between the run canvas and the step details. */
export function RunSplitHandle({ binding }: Readonly<{ binding: RunSplitHandleBinding }>) {
  const { t } = useTranslation();
  const { percent, ...handlers } = binding;
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-valuemin={SPLIT_MIN}
      aria-valuemax={SPLIT_MAX}
      aria-valuenow={percent}
      aria-label={t('workflow.canvas.splitHandle')}
      tabIndex={0}
      className="run-split-handle"
      {...handlers}
    >
      <span className="run-split-handle__grip" aria-hidden="true" />
    </div>
  );
}
