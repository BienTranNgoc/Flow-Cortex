import type { RunStepResponse } from '@flow-cortex/api-client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { IterateResult } from './IterateResult';
import { ResultTable } from './ResultTable';
import { codePointLength, fileCounts, stepOutcome, type FileCounts } from './runResultModel';
import './StepResult.css';
import { StepOutput } from './StepOutput';
import { StoryboardOutputView } from './StoryboardOutputView';

const COPIED_MS = 2000;
const FILE_KINDS = ['video', 'audio', 'image', 'file'] as const;

interface TextResultProps {
  text: string;
  /** The accessible name of the scrolling block. */
  label: string;
  /** Offers "Copy" for the shown text. */
  copy: boolean;
}

/** Text as text: its length, a bounded block that scrolls inside, and optionally "Copy". */
export function TextResult({ text, label, copy }: Readonly<TextResultProps>) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const alive = useRef(true);
  const length = useMemo(() => codePointLength(text), [text]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      clearTimeout(timer.current);
    };
  }, []);

  const copyText = async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // A refused or missing clipboard is shown as a sentence, not thrown.
      if (alive.current) {
        setCopied(false);
        setFailed(true);
      }
      return;
    }
    if (!alive.current) {
      return;
    }
    clearTimeout(timer.current);
    setFailed(false);
    setCopied(true);
    timer.current = setTimeout(() => setCopied(false), COPIED_MS);
  };

  return (
    <div className="result-text">
      <div className="result-text-bar">
        <span className="result-summary">
          {t('workflow.result.characters', {
            count: length,
            n: formatNumber(length, activeLocale()),
          })}
        </span>
        {copy && (
          <button type="button" className="result-copy" onClick={() => void copyText()}>
            {copied ? t('workflow.result.copied') : t('workflow.result.copy')}
          </button>
        )}
      </div>
      {copy && (
        <>
          <span role="status" className="visually-hidden">
            {copied ? t('workflow.result.copied') : ''}
          </span>
          <p role="status" className="result-note result-copy-failed">
            {failed ? t('workflow.result.copyFailed') : ''}
          </p>
        </>
      )}
      <div role="region" aria-label={label} tabIndex={0} className="result-text-block">
        <p className="run-step-output result-text-body">{text}</p>
      </div>
    </div>
  );
}

/** "2 videos · 1 image · 1 file": the stored files by kind, nothing without any. */
function FilesSummary({ counts }: Readonly<{ counts: FileCounts }>) {
  const { t } = useTranslation();
  const parts = FILE_KINDS.filter((kind) => counts[kind] > 0).map((kind) =>
    t(`workflow.result.files.${kind}`, { count: counts[kind] }),
  );
  return parts.length > 0 ? (
    <p className="result-summary result-files">{parts.join(' · ')}</p>
  ) : null;
}

interface StepOutcomeProps {
  step: RunStepResponse;
  /** The result text: the step's output, or what replaced it. */
  text: string | undefined;
}

/** A completed step's result, drawn by what it holds; the files' own viewers are listed by the caller. */
export function StepOutcome({ step, text }: Readonly<StepOutcomeProps>) {
  const { t } = useTranslation();
  const outcome = useMemo(() => stepOutcome(step, text), [step, text]);
  const counts = useMemo(() => fileCounts(step.outputs ?? []), [step.outputs]);

  let body = null;
  switch (outcome.kind) {
    case 'storyboard':
      body = <StoryboardOutputView storyboard={outcome.storyboard} />;
      break;
    case 'iterate':
      body = <IterateResult step={step} />;
      break;
    case 'table':
      body = <ResultTable rows={outcome.rows} columns={outcome.columns} />;
      break;
    case 'report':
      body = <StepOutput output={text ?? ''} />;
      break;
    case 'text':
      body = <TextResult text={outcome.text} label={t('workflow.result.textRegion')} copy />;
      break;
    case 'empty':
      body = <p className="result-note result-empty">{t('workflow.result.empty')}</p>;
      break;
    case 'files':
    case 'details':
      break;
  }
  return (
    <>
      {body}
      <FilesSummary counts={counts} />
    </>
  );
}
