import { localeOf } from '@flow-cortex/i18n';
import { Markdown } from '@flow-cortex/ui-kit';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { formatEffectiveDate, formatPublishedAt, type LegalDocumentView } from './legalDocument';

interface LegalDocumentBodyProps {
  readonly view: LegalDocumentView;
  readonly publishedAt: string;
  /** Shown above the title, e.g. the notice on an earlier version. */
  readonly notice?: ReactNode;
  /** Shown below the text. */
  readonly footer?: ReactNode;
}

/**
 * One published legal document: title, version and dates, then the body through the shared Markdown
 * renderer, which shows raw HTML as text and refuses unsafe links and images. The body headings start
 * at h3 under the page h1 and a hidden h2.
 */
export function LegalDocumentBody({ view, publishedAt, notice, footer }: Readonly<LegalDocumentBodyProps>) {
  const { t, i18n } = useTranslation();
  const locale = localeOf(i18n);

  return (
    <article className="legal-doc">
      {notice}
      <header className="legal-doc__header">
        <h1 className="legal-doc__title">{view.title}</h1>
        <dl className="legal-doc__meta">
          {view.docVersion && (
            <div>
              <dt>{t('legal.document.version')}</dt>
              <dd>{view.docVersion}</dd>
            </div>
          )}
          {view.effectiveDate && (
            <div>
              <dt>{t('legal.document.effectiveDate')}</dt>
              <dd>{formatEffectiveDate(view.effectiveDate, locale)}</dd>
            </div>
          )}
          <div>
            <dt>{t('legal.document.publishedAt')}</dt>
            <dd>{formatPublishedAt(publishedAt, locale)}</dd>
          </div>
        </dl>
      </header>
      <h2 className="visually-hidden">{t('legal.document.textHeading')}</h2>
      <div className="legal-doc__text">
        <Markdown firstHeadingLevel={3}>{view.body}</Markdown>
      </div>
      {footer}
    </article>
  );
}
