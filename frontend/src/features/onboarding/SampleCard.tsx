import type { OnboardingSampleWorkflow } from '@flow-cortex/api-client';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';

interface SampleCardProps {
  sample: OnboardingSampleWorkflow;
  /** The journey is finishing: the button is inert and says so. */
  busy: boolean;
  onTry: (sample: OnboardingSampleWorkflow) => void;
}

/** The workflow the admin picked for newcomers, with a three-line preview of its input. */
export function SampleCard({ sample, busy, onTry }: Readonly<SampleCardProps>) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section className="onboarding-sample" aria-labelledby={headingId}>
      <p className="onboarding-sample__caption">{t('onboarding.done.sample.caption')}</p>
      <h3 id={headingId} className="onboarding-sample__name">
        {sample.name}
      </h3>
      <p className="onboarding-sample__input">{sample.input}</p>
      <button
        type="button"
        className="onboarding-sample__try"
        aria-label={t('onboarding.done.sample.tryNamed', { name: sample.name })}
        aria-disabled={busy}
        aria-busy={busy}
        onClick={() => {
          if (!busy) onTry(sample);
        }}
      >
        {busy ? t('onboarding.done.sample.trying') : t('onboarding.done.sample.try')}
      </button>
    </section>
  );
}
