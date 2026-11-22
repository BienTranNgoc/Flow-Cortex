import type { SuggestionResponse } from '@flow-cortex/api-client';
import { Image, SpeakerHigh, TextAa, VideoCamera, type Icon } from '@phosphor-icons/react';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../../lib/i18n';
import { problemDetailOf } from '../../../lib/problemMessage';
import { DriveFailed, DriveLoading } from '../shared/DriveStates';
import { SignedMedia } from '../shared/SignedMedia';
import { formatWhen } from './formFields';
import { KeepDialog } from './KeepDialog';
import { useDriveSuggestionsQuery } from './useDriveItems';
import '../shared/DriveShared.css';
import './DriveItems.css';

const KIND_ICONS: Record<SuggestionResponse['kind'], Icon> = {
  text: TextAa,
  image: Image,
  video: VideoCamera,
  audio: SpeakerHigh,
};

/** Whether a candidate can become an item: a text that fits the limit, or a media output of a run. */
function canKeep(candidate: SuggestionResponse): boolean {
  return candidate.kind === 'text'
    ? !candidate.tooLong && candidate.textValue !== undefined
    : candidate.runOutput !== undefined;
}

/**
 * Recent run outputs and inputs worth keeping; a kept one reads "Kept" until the page is left.
 * The API returns an already-kept candidate again, so the owner of the page remembers `kept` for the visit.
 */
export function SuggestionsSection({
  kept,
  onKept,
}: {
  kept: ReadonlySet<string>;
  onKept: (suggestionId: string) => void;
}) {
  const { t } = useTranslation();
  const headingId = useId();
  const suggestions = useDriveSuggestionsQuery();
  const [keeping, setKeeping] = useState<SuggestionResponse | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const locale = activeLocale();

  let body;
  if (suggestions.isPending) {
    body = <DriveLoading />;
  } else if (suggestions.isError) {
    body = (
      <DriveFailed
        message={problemDetailOf(suggestions.error) ?? t('drive.status.failed')}
        onRetry={() => void suggestions.refetch()}
      />
    );
  } else if (suggestions.data.length === 0) {
    body = <p className="drive-items__empty">{t('driveItems.suggestions.empty')}</p>;
  } else {
    body = (
      <ul className="drive-grid">
        {suggestions.data.map((candidate) => {
          const Glyph = KIND_ICONS[candidate.kind];
          const kindLabel = t(`driveItems.types.${candidate.kind}`);
          return (
            <li key={candidate.id} className="drive-card">
              <div className="drive-card__head">
                <Glyph aria-hidden="true" className="drive-card__icon" />
                <span className="drive-card__kind">{kindLabel}</span>
              </div>
              {candidate.kind === 'text' ? (
                <p className="drive-card__text">{candidate.previewText}</p>
              ) : (
                <SignedMedia
                  kind={candidate.kind}
                  url={candidate.url}
                  expiresAt={candidate.urlExpiresAt}
                  label={t('driveItems.suggestions.mediaLabel', {
                    kind: kindLabel,
                    workflow: candidate.workflowName,
                  })}
                  onRenew={() => void suggestions.refetch()}
                  className="drive-card__media"
                />
              )}
              <p className="drive-card__meta">
                {t('driveItems.suggestions.from', { workflow: candidate.workflowName })}
              </p>
              <p className="drive-card__meta">
                {formatWhen(candidate.at, locale)}
              </p>
              <div className="drive-card__actions">
                {canKeep(candidate) && (
                  // The same button turns into "Kept", so focus has somewhere to return to.
                  <button
                    type="button"
                    className="drive-button"
                    aria-disabled={kept.has(candidate.id) || undefined}
                    aria-label={
                      kept.has(candidate.id)
                        ? undefined
                        : t('driveItems.suggestions.keepLabel', {
                            kind: kindLabel,
                            workflow: candidate.workflowName,
                          })
                    }
                    onClick={() => {
                      if (!kept.has(candidate.id)) setKeeping(candidate);
                    }}
                  >
                    {kept.has(candidate.id) ? t('driveItems.suggestions.kept') : t('driveItems.suggestions.keep')}
                  </button>
                )}
                {candidate.kind === 'text' && candidate.tooLong && (
                  <p className="drive-card__state">{t('driveItems.suggestions.tooLong')}</p>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    );
  }

  return (
    <section className="drive-suggestions" aria-labelledby={headingId}>
      <h2 id={headingId} className="drive-section__title">
        {t('driveItems.suggestions.title')}
      </h2>
      <p className="drive-section__hint">{t('driveItems.suggestions.hint')}</p>
      <output className="visually-hidden">{announcement}</output>
      {body}
      <KeepDialog
        candidate={keeping}
        onClose={() => setKeeping(null)}
        onKept={(candidate, name) => {
          onKept(candidate.id);
          setAnnouncement(t('driveItems.suggestions.keptAnnouncement', { name }));
          setKeeping(null);
        }}
      />
    </section>
  );
}
