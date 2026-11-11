import { getGetContentMediaUrl, type ContentMediaItem } from '@flow-cortex/api-client';
import { Markdown } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import '../support/markdown.css';
import {
  MEDIA,
  RICH_TEXT,
  mediaIdOf,
  readText,
  type ContentLocale,
  type DocumentContent,
} from './documentModel';
import { MediaThumb } from './MediaThumb';

interface DocumentPreviewProps {
  readonly content: DocumentContent;
  readonly locale: ContentLocale;
  readonly mediaItems: Record<string, ContentMediaItem>;
}

/** The editor's current content as a reader of one language would see it; nothing is stored or fetched. */
export function DocumentPreview({ content, locale, mediaItems }: DocumentPreviewProps) {
  const { t } = useTranslation();
  const { sections, seoMeta } = content;
  const rendered = sections.filter(
    (section) => section.type === RICH_TEXT || section.type === MEDIA,
  );

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
