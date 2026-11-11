import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import type { WorkflowExampleResponse } from '@flow-cortex/api-client';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { problemDetailOf } from '../../lib/problemMessage';
import { useUseWorkflowExampleMutation, useWorkflowExamplesQuery } from './useWorkflows';
import './WorkflowExamples.css';

const COPIED_MS = 2000;

function CopyButton({ text }: Readonly<{ text: string }>) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), COPIED_MS);
  }

  return (
    <>
      <button type="button" className="wf-examples__copy" onClick={() => void copy()}>
        {copied ? t('workflow.examples.copied') : t('workflow.examples.copy')}
      </button>
      <span className="visually-hidden" aria-live="polite">
        {copied ? t('workflow.examples.copied') : ''}
      </span>
    </>
  );
}

/** Starter workflows a member can copy into a draft of their own. */
export function WorkflowExamples() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const examples = useWorkflowExamplesQuery();
  const create = useUseWorkflowExampleMutation();
  const locale = activeLocale();

  if (examples.isError) {
    return (
      <section className="wf-examples" aria-labelledby="wf-examples-heading">
        <h2 id="wf-examples-heading" className="wf-examples__heading">
          {t('workflow.examples.heading')}
        </h2>
        <StatusBlock
          kind="error"
          message={problemDetailOf(examples.error) ?? t('workflow.examples.loadError')}
          action={{ label: t('common.state.retry'), onClick: () => void examples.refetch() }}
        />
      </section>
    );
  }

  if (examples.isPending) {
    return (
      <section className="wf-examples" aria-labelledby="wf-examples-heading" aria-busy="true">
        <h2 id="wf-examples-heading" className="wf-examples__heading">
          {t('workflow.examples.heading')}
        </h2>
        <output className="visually-hidden">{t('workflow.examples.loading')}</output>
        <div className="wf-examples__grid">
          <Skeleton shape="card" count={3} />
        </div>
      </section>
    );
  }

  if (examples.data.length === 0) return null;

  function use(example: WorkflowExampleResponse) {
    create.mutate(example.id, {
      onSuccess: (workflow) => {
        const target = `/workflows/${workflow.id}`;
        if (example.sampleInput) {
          void navigate(target, { state: { sampleInput: example.sampleInput } });
        } else {
          void navigate(target);
        }
      },
    });
  }

  return (
    <section className="wf-examples" aria-labelledby="wf-examples-heading">
      <h2 id="wf-examples-heading" className="wf-examples__heading">
        {t('workflow.examples.heading')}
      </h2>
      <ul className="wf-examples__grid">
        {examples.data.map((example) => {
          const pressed = create.variables === example.id;
          const busy = create.isPending && pressed;
          const failed = create.isError && pressed;
          return (
            <li key={example.id} className="wf-examples__card">
              <h3 className="wf-examples__title">{example.title}</h3>
              <p className="wf-examples__description">{example.description}</p>
              <p className="wf-examples__meta">
                <span>
                  {t('workflow.examples.stepCount', {
                    count: example.stepCount,
                    n: formatNumber(example.stepCount, locale),
                  })}
                </span>
                {example.estimatedCredits != null && (
                  <span>
                    {t('workflow.examples.credits', {
                      n: formatNumber(example.estimatedCredits, locale),
                    })}
                  </span>
                )}
              </p>
              {example.sampleInput != null && (
                <div className="wf-examples__sample">
                  <span className="wf-examples__sample-label">
                    {t('workflow.examples.sampleInput')}
                  </span>
                  <pre className="wf-examples__sample-text">{example.sampleInput}</pre>
                  <CopyButton text={example.sampleInput} />
                </div>
              )}
              {failed && (
                <p className="wf-examples__error" role="alert">
                  {problemDetailOf(create.error) ?? t('workflow.examples.createError')}
                </p>
              )}
              <button
                type="button"
                className="cortex-btn cortex-btn--primary wf-examples__use"
                disabled={busy}
                aria-busy={busy}
                onClick={() => use(example)}
              >
                {busy ? t('workflow.examples.busy') : t('workflow.examples.use')}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
