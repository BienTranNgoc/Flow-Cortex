import { getGetContentMediaUrl, type ContentMediaItem } from '@flow-cortex/api-client';
import { Markdown } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import '../support/markdown.css';
import {
  LEGAL_DOC,
  LEGAL_INDEX,
  RICH_TEXT,
  isEditable,
  mediaIdOf,
  readText,
  type ContentLocale,
  type DocumentContent,
  type Section,
} from './documentModel';
import { LegalIndexPreview } from './LegalIndexEditor';
import { MediaThumb } from './MediaThumb';

/** A stored `YYYY-MM-DD` as a calendar date of `locale`; anything else is shown as typed. */
function calendarDate(value: string, locale: ContentLocale): string {
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(date);
}

function LegalDocPreview({ section, locale }: { section: Section; locale: ContentLocale }) {
  const { t } = useTranslation();
  const title = readText(section.data.title)[locale];
  const body = readText(section.data.body)[locale];
  const version = typeof section.data.docVersion === 'string' ? section.data.docVersion : '';
  const date = typeof section.data.effectiveDate === 'string' ? section.data.effectiveDate : '';
  const meta = [
    version && `${t('content.fields.docVersion')}: ${version}`,
    date && `${t('content.fields.effectiveDate')}: ${calendarDate(date, locale)}`,
  ].filter(Boolean);
  return (
    <section className="content-preview__legal">
      <h2>{title.trim() || t('content.preview.blankText')}</h2>
      {meta.length > 0 && <p className="content-hint">{meta.join(' · ')}</p>}
      {body.trim() ? (
        <Markdown firstHeadingLevel={3} linksInNewTab>
          {body}
        </Markdown>
      ) : (
        <p className="content-hint">{t('content.preview.blankText')}</p>
      )}
    </section>
  );
}

interface DocumentPreviewProps {
  readonly content: DocumentContent;
  readonly locale: ContentLocale;
  readonly mediaItems: Record<string, ContentMediaItem>;
}

/** The editor's current content as a reader of one language would see it; nothing is stored or fetched. */
export function DocumentPreview({ content, locale, mediaItems }: DocumentPreviewProps) {
  const { t } = useTranslation();
  const { sections, seoMeta } = content;
  const rendered = sections.filter((section) => isEditable(section.type));

  return (
    <article className="content-preview" lang={locale} aria-label={t('content.preview.label')}>
      {(seoMeta.title[locale] || seoMeta.description[locale]) && (
        <header className="content-preview__seo">
          <p className="content-preview__seo-title">{seoMeta.title[locale]}</p>
          <p>{seoMeta.description[locale]}</p>
        </header>
      )}
      {rendered.length === 0 && <p className="content-hint">{t('content.preview.empty')}</p>}
      {rendered.map((section, position) => {
        if (section.type === LEGAL_DOC) {
          return <LegalDocPreview key={position} section={section} locale={locale} />;
        }
        if (section.type === LEGAL_INDEX) {
          return <LegalIndexPreview key={position} section={section} locale={locale} />;
        }
        if (section.type === RICH_TEXT) {
          const body = readText(section.data.body)[locale];
          return body.trim() ? (
            <Markdown key={position} firstHeadingLevel={3} linksInNewTab>
              {body}
            </Markdown>
          ) : (
            <p key={position} className="content-hint">
              {t('content.preview.blankText')}
            </p>
          );
        }
        const mediaId = mediaIdOf(section);
        if (!mediaId) {
          return (
            <p key={position} className="content-hint">
              {t('content.preview.noMedia')}
            </p>
          );
        }
        const caption = readText(section.data.caption)[locale];
        const item = mediaItems[mediaId];
        return (
          <figure key={`${position}-${mediaId}`} className="content-preview__figure">
            <MediaThumb
              url={getGetContentMediaUrl(mediaId)}
              kind={item?.kind}
              alt={readText(section.data.alt)[locale]}
              variant="full"
            />
            {caption.trim() && <figcaption>{caption}</figcaption>}
          </figure>
        );
      })}
    </article>
  );
}
