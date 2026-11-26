import { useTranslation } from 'react-i18next';
import { documentTitle } from './documentTitle';
import {
  MAX_TITLE_CHARS,
  indexItems,
  moved,
  withIndexItems,
  withItemLabel,
  type ContentLocale,
  type IndexItem,
  type Section,
} from './documentModel';
import { errorAt, labelPath, slugPath, visiblePath } from './fieldPaths';
import { legalDocumentName, useLegalDocuments } from './legalDocuments';
import { TextField } from './TextField';

interface LegalIndexEditorProps {
  readonly section: Section;
  readonly index: number;
  readonly locale: ContentLocale;
  readonly readOnly: boolean;
  /** Field path to the API's message from the last refused save. */
  readonly errors: Record<string, string>;
  /** `path` is absent for a change that shifts rows, which makes every refused path stale. */
  readonly onEdit: (section: Section, path?: string) => void;
}

/** The ordered rows of a `legalIndex` section: which legal documents are listed, shown, and how they read. */
export function LegalIndexEditor({
  section,
  index,
  locale,
  readOnly,
  errors,
  onEdit,
}: LegalIndexEditorProps) {
  const { t, i18n } = useTranslation();
  const documents = useLegalDocuments();
  const items = indexItems(section);
  const offered = documents.filter(
    (document) => !items.some((item) => item.slug === document.slug),
  );
  const language = t(`content.languages.${locale}`);

  const replace = (next: readonly IndexItem[], path?: string) =>
    onEdit(withIndexItems(section, next), path);
  const update = (position: number, item: IndexItem, path: string) =>
    replace(
      items.map((existing, i) => (i === position ? item : existing)),
      path,
    );

  return (
    <div className="content-index">
      {items.length === 0 && <p className="content-hint">{t('content.legalIndex.empty')}</p>}
      {items.length > 0 && (
        <ul className="content-index__rows">
          {items.map((item, position) => {
            const name = legalDocumentName(documents, item.slug, locale, i18n.language);
            const slugError = errors[slugPath(index, position)];
            const shownError = errors[visiblePath(index, position)];
            const labelError = errorAt(errors, labelPath(index, position, locale));
            return (
              <li key={item.slug}>
                <fieldset className="content-index__row">
                  <legend>{name}</legend>
                  {(slugError || shownError) && (
                    <p className="cortex-admin-form-error" role="alert">
                      {slugError ?? shownError}
                    </p>
                  )}
                  <label className="content-index__shown">
                    <input
                      type="checkbox"
                      checked={item.visible}
                      disabled={readOnly}
                      onChange={(event) =>
                        update(
                          position,
                          { ...item, visible: event.target.checked },
                          visiblePath(index, position),
                        )
                      }
                    />
                    {t('content.fields.shown')}
                  </label>
                  <TextField
                    label={t('content.editor.fieldIn', {
                      field: t('content.fields.label'),
                      language,
                    })}
                    value={item.label[locale] ?? ''}
                    locale={locale}
                    max={MAX_TITLE_CHARS}
                    placeholder={name}
                    readOnly={readOnly}
                    error={labelError}
                    onChange={(value) =>
                      update(
                        position,
                        withItemLabel(item, locale, value),
                        labelPath(index, position, locale),
                      )
                    }
                  />
                  {!readOnly && (
                    <div className="content-section__controls">
                      <button
                        type="button"
                        className="cortex-admin-btn cortex-admin-btn--sm"
                        aria-label={t('content.legalIndex.moveUp', { name })}
                        aria-disabled={position === 0}
                        onClick={() => position > 0 && replace(moved(items, position, -1))}
                      >
                        {t('content.editor.up')}
                      </button>
                      <button
                        type="button"
                        className="cortex-admin-btn cortex-admin-btn--sm"
                        aria-label={t('content.legalIndex.moveDown', { name })}
                        aria-disabled={position === items.length - 1}
                        onClick={() =>
                          position < items.length - 1 && replace(moved(items, position, 1))
                        }
                      >
                        {t('content.editor.down')}
                      </button>
                      <button
                        type="button"
                        className="cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger"
                        aria-label={t('content.legalIndex.remove', { name })}
                        onClick={() => replace(items.filter((_, i) => i !== position))}
                      >
                        {t('content.editor.removeShort')}
                      </button>
                    </div>
                  )}
                </fieldset>
              </li>
            );
          })}
        </ul>
      )}
      {!readOnly && offered.length > 0 && (
        <div className="cortex-admin-field content-index__add">
          <select
            aria-label={t('content.legalIndex.addDocument')}
            value=""
            onChange={(event) => {
              const slug = event.target.value;
              if (slug) replace([...items, { slug, visible: true, label: {} }]);
            }}
          >
            <option value="" disabled>
              {t('content.legalIndex.addDocument')}
            </option>
            {offered.map((document) => (
              <option key={document.slug} value={document.slug}>
                {documentTitle(document, i18n.language)}
              </option>
            ))}
          </select>
        </div>
      )}
    </div>
  );
}

interface LegalIndexPreviewProps {
  readonly section: Section;
  readonly locale: ContentLocale;
}

/** The shown rows in order, each by its label for the language, as a reader would see the index. */
export function LegalIndexPreview({ section, locale }: LegalIndexPreviewProps) {
  const { t, i18n } = useTranslation();
  const documents = useLegalDocuments();
  const shown = indexItems(section).filter((item) => item.visible);
  if (shown.length === 0) return <p className="content-hint">{t('content.legalIndex.empty')}</p>;
  return (
    <ol className="content-preview__index">
      {shown.map((item) => (
        <li key={item.slug}>
          {item.label[locale]?.trim() ||
            legalDocumentName(documents, item.slug, locale, i18n.language)}
        </li>
      ))}
    </ol>
  );
}
