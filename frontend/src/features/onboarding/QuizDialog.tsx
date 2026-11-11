import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { JourneyDialog } from './JourneyDialog';
import {
  AI_LEVELS,
  CONTENT_TYPES,
  INDUSTRIES,
  PURPOSES,
  QUESTION_COUNT,
  SOURCES,
  USAGE_TYPES,
  type QuizQuestion,
} from './onboardingVocabulary';
import type { Answers } from './useOnboarding';

function toggled<T extends string>(
  current: readonly T[] | null,
  option: T,
  vocabulary: readonly T[],
): readonly T[] {
  const picked = new Set(current ?? []);
  if (!picked.delete(option)) picked.add(option);
  return vocabulary.filter((value) => picked.has(value));
}

interface ChoicesProps<T extends string> {
  question: QuizQuestion;
  options: readonly T[];
  labelledBy: string;
  selected: readonly T[];
  onPick: (option: T) => void;
  multi: boolean;
  disabled: boolean;
}

function Choices<T extends string>({
  question,
  options,
  labelledBy,
  selected,
  onPick,
  multi,
  disabled,
}: Readonly<ChoicesProps<T>>) {
  const { t } = useTranslation();
  const name = useId();
  return (
    <div
      className="onboarding-choices"
      role={multi ? 'group' : 'radiogroup'}
      aria-labelledby={labelledBy}
    >
      {options.map((option) => {
        const label = t(`onboarding.quiz.q${question}.options.${option}`);
        const on = selected.includes(option);
        if (multi) {
          return (
            <button
              key={option}
              type="button"
              className="onboarding-chip"
              aria-pressed={on}
              aria-disabled={disabled}
              onClick={() => {
                if (!disabled) onPick(option);
              }}
            >
              {label}
            </button>
          );
        }
        return (
          <label key={option} className="onboarding-chip" data-checked={on}>
            <input
              type="radio"
              name={name}
              checked={on}
              disabled={disabled}
              onChange={() => onPick(option)}
            />
            <span>{label}</span>
          </label>
        );
      })}
    </div>
  );
}

interface QuizDialogProps {
  question: QuizQuestion;
  answers: Answers;
  saving: boolean;
  error: boolean;
  onNext: (answers: Answers) => void;
  onBack: () => void;
  onSkip: () => void;
  onClose: () => void;
}

export function QuizDialog({
  question,
  answers,
  saving,
  error,
  onNext,
  onBack,
  onSkip,
  onClose,
}: Readonly<QuizDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const [draft, setDraft] = useState(answers);
  const last = question === QUESTION_COUNT;

  const choices = (() => {
    switch (question) {
      case 1:
        return (
          <Choices
            question={question}
            options={PURPOSES}
            labelledBy={titleId}
            selected={draft.purposes ?? []}
            onPick={(option) =>
              setDraft((d) => ({ ...d, purposes: toggled(d.purposes, option, PURPOSES) }))
            }
            multi
            disabled={saving}
          />
        );
      case 2:
        return (
          <Choices
            question={question}
            options={SOURCES}
            labelledBy={titleId}
            selected={draft.source ? [draft.source] : []}
            onPick={(option) => setDraft((d) => ({ ...d, source: option }))}
            multi={false}
            disabled={saving}
          />
        );
      case 3:
        return (
          <Choices
            question={question}
            options={AI_LEVELS}
            labelledBy={titleId}
            selected={draft.aiLevel ? [draft.aiLevel] : []}
            onPick={(option) => setDraft((d) => ({ ...d, aiLevel: option }))}
            multi={false}
            disabled={saving}
          />
        );
      case 4:
        return (
          <Choices
            question={question}
            options={INDUSTRIES}
            labelledBy={titleId}
            selected={draft.industries ?? []}
            onPick={(option) =>
              setDraft((d) => ({ ...d, industries: toggled(d.industries, option, INDUSTRIES) }))
            }
            multi
            disabled={saving}
          />
        );
      case 5:
        return (
          <Choices
            question={question}
            options={CONTENT_TYPES}
            labelledBy={titleId}
            selected={draft.contentTypes ?? []}
            onPick={(option) =>
              setDraft((d) => ({
                ...d,
                contentTypes: toggled(d.contentTypes, option, CONTENT_TYPES),
              }))
            }
            multi
            disabled={saving}
          />
        );
      case 6:
        return (
          <Choices
            question={question}
            options={USAGE_TYPES}
            labelledBy={titleId}
            selected={draft.usageType ? [draft.usageType] : []}
            onPick={(option) => setDraft((d) => ({ ...d, usageType: option }))}
            multi={false}
            disabled={saving}
          />
        );
    }
  })();

  const multi = question === 1 || question === 4 || question === 5;

  return (
    <JourneyDialog
      titleId={titleId}
      title={t(`onboarding.quiz.q${question}.title`)}
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            onClick={() => {
              if (!saving) onBack();
            }}
            aria-disabled={saving}
          >
            {t('onboarding.quiz.back')}
          </button>
          <button
            type="button"
            onClick={() => {
              if (!saving) onSkip();
            }}
            aria-disabled={saving}
          >
            {t('onboarding.quiz.skip')}
          </button>
          <button
            type="button"
            className="onboarding-primary"
            aria-disabled={saving}
            aria-busy={saving}
            onClick={() => {
              if (!saving) onNext(draft);
            }}
          >
            {saving
              ? t('onboarding.quiz.saving')
              : error
                ? t('onboarding.quiz.retry')
                : t(last ? 'onboarding.quiz.finish' : 'onboarding.quiz.next')}
          </button>
        </>
      }
    >
      <p className="onboarding-counter">
        {t('onboarding.quiz.counter', { n: question, total: QUESTION_COUNT })}
      </p>
      <p>{t(multi ? 'onboarding.quiz.multiHint' : 'onboarding.quiz.singleHint')}</p>
      {choices}
      {error && (
        <p role="alert" className="form-error">
          {t('onboarding.quiz.saveFailed')}
        </p>
      )}
    </JourneyDialog>
  );
}
