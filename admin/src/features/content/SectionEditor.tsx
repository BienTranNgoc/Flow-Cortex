import { getGetContentMediaUrl, type ContentMediaItem } from '@flow-cortex/api-client';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { errorAt, mediaPath, plainPath, textPath, type PlainPathField } from './fieldPaths';
import {
  DOC_VERSION,
  EFFECTIVE_DATE,
  LEGAL_DOC,
  LEGAL_INDEX,
  MAX_DOC_VERSION_CHARS,
  MAX_TEXT_CHARS,
  MAX_TITLE_CHARS,
  MEDIA,
  RICH_TEXT,
  mediaIdOf,
  readText,
  withField,
  withText,
  type ContentLocale,
  type Section,
} from './documentModel';
import { LegalIndexEditor } from './LegalIndexEditor';
import { MediaThumb } from './MediaThumb';
import { TextField } from './TextField';

interface SectionEditorProps {
  readonly section: Section;
  readonly index: number;
  readonly locale: ContentLocale;
  readonly readOnly: boolean;
  /** Field path to the API's message from the last refused save. */
  readonly errors: Record<string, string>;
  /** The caller may open the library (`content.media.view`). */
  readonly canBrowseMedia: boolean;
  /** The library item the section names, when the picker handed it over in this session. */
  readonly mediaItem: ContentMediaItem | undefined;
  /** `path` is the field the edit touched; absent for an edit that shifts rows. */
  readonly onEdit: (section: Section, path?: string) => void;
  readonly onPickMedia: () => void;
}

/** The fields of one section, for the language tab the editor shows. */
export function SectionEditor({
  section,
  index,
  locale,
  readOnly,
  errors,
  canBrowseMedia,
  mediaItem,
  onEdit,
  onPickMedia,
}: SectionEditorProps) {
  const { t } = useTranslation();
  const language = t(`content.languages.${locale}`);
  const hintId = useId();

  const text = (field: 'body' | 'alt' | 'caption' | 'title') => {
    const path = textPath(index, field, locale);
    return {
      value: readText(section.data[field])[locale],
      locale,
      readOnly,
      error: errorAt(errors, path),
      onChange: (value: string) => onEdit(withText(section, field, locale, value), path),
    };
  };

  const fields = {
    body: text('body'),
    alt: text('alt'),
    caption: text('caption'),
    title: text('title'),
  };

  const plain = (field: PlainPathField) => {
    const path = plainPath(index, field);
    const value = section.data[field];
    return {
      value: typeof value === 'string' ? value : '',
      readOnly,
      hint: t('content.editor.sharedField'),
      error: errors[path],
      onChange: (next: string) => onEdit(withField(section, field, next), path),
    };
  };

  if (section.type === LEGAL_DOC) {
    return (
      <>
        <TextField
          label={t('content.editor.fieldIn', { field: t('content.fields.title'), language })}
          max={MAX_TITLE_CHARS}
          {...fields.title}
        />
        <TextField
          label={t('content.editor.fieldIn', { field: t('content.fields.body'), language })}
          max={MAX_TEXT_CHARS}
          rows={20}
          {...fields.body}
        />
        <TextField
          label={t('content.fields.docVersion')}
          max={MAX_DOC_VERSION_CHARS}
          {...plain(DOC_VERSION)}
        />
        <TextField
          label={t('content.fields.effectiveDate')}
          type="date"
          {...plain(EFFECTIVE_DATE)}
        />
      </>
    );
  }

  if (section.type === LEGAL_INDEX) {
    return (
      <LegalIndexEditor
        section={section}
        index={index}
        locale={locale}
        readOnly={readOnly}
        errors={errors}
        onEdit={onEdit}
      />
    );
  }

  if (section.type === RICH_TEXT) {
    return (
      <TextField
        label={t('content.editor.fieldIn', { field: t('content.fields.body'), language })}
        max={MAX_TEXT_CHARS}
        rows={12}
        {...fields.body}
      />
    );
  }

  if (section.type === MEDIA) {
    const mediaId = mediaIdOf(section);
    const mediaError = errors[mediaPath(index)];
    const alt = readText(section.data.alt)[locale];
    return (
      <>
        <div className="content-media-slot">
          <div className="content-media-slot__preview">
            {mediaId ? (
              <MediaThumb
                key={mediaId}
                url={getGetContentMediaUrl(mediaId)}
                kind={mediaItem?.id === mediaId ? mediaItem.kind : undefined}
                alt={alt}
              />
            ) : (
              <span className="content-thumb content-thumb--missing">
                {t('content.editor.noMedia')}
              </span>
            )}
          </div>
          {!readOnly && (
            <div className="content-media-slot__actions">
              <button
                type="button"
                className="cortex-admin-btn"
                disabled={!canBrowseMedia}
                aria-describedby={canBrowseMedia ? undefined : hintId}
                onClick={onPickMedia}
              >
                {mediaId ? t('content.editor.replaceMedia') : t('content.editor.chooseMedia')}
              </button>
              {!canBrowseMedia && (
                <small id={hintId} className="content-hint">
                  {t('content.editor.needsMediaView')}
                </small>
              )}
            </div>
          )}
        </div>
        {mediaError && (
          <p className="cortex-admin-form-error" role="alert">
            {mediaError}
          </p>
        )}
        <TextField
          label={t('content.editor.fieldIn', { field: t('content.fields.alt'), language })}
          max={MAX_TEXT_CHARS}
          {...fields.alt}
        />
        <TextField
          label={t('content.editor.fieldIn', { field: t('content.fields.caption'), language })}
          max={MAX_TEXT_CHARS}
          {...fields.caption}
        />
      </>
    );
  }

  return (
    <p className="content-hint">{t('content.editor.unsupportedType', { type: section.type })}</p>
  );
}
