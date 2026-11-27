import type { RunInsightResponse } from '@flow-cortex/api-client';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import './RunExtras.css';

/** The model's short reading of a table result: a headline and a few highlights. */
export function RunInsightCard({ insight }: Readonly<{ insight: RunInsightResponse }>) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section className="run-insight" aria-labelledby={headingId}>
      <h2 id={headingId} className="visually-hidden">
        {t('workflow.insight.title')}
      </h2>
      <p className="run-insight-headline">{insight.headline}</p>
      {insight.highlights.length > 0 && (
        <ul className="run-insight-highlights">
          {insight.highlights.map((highlight, i) => (
            <li key={`${i}-${highlight}`}>{highlight}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
