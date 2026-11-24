import { CaretLeft, CaretRight } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../../lib/i18n';
import { formatNumber } from '../../../lib/format';
import { pageWindow } from '../model/pageWindow';
import './DriveShared.css';

interface PaginationProps {
  /** The 0-based page shown, as the API counts it. */
  page: number;
  /** Entries over every page. */
  total: number;
  size: number;
  onPage: (page: number) => void;
  /** With both, a select of page sizes joins the buttons. */
  sizes?: readonly number[];
  onSize?: (size: number) => void;
}

/** Previous, numbered and next page buttons, with an optional page-size select; nothing for a single page and no size choice. */
export function Pagination({ page, total, size, onPage, sizes, onSize }: Readonly<PaginationProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const pages = Math.max(1, Math.ceil(total / size));
  const showSizes = sizes !== undefined && onSize !== undefined && total > 0;
  if (pages === 1 && !showSizes) return null;

  return (
    <div className="drive-pagination">
      {pages > 1 && (
        <nav aria-label={t('drive.pagination.label')}>
          <ul className="drive-pagination__pages">
            <li>
              <button
                type="button"
                className="drive-button"
                aria-label={t('drive.pagination.previous')}
                disabled={page <= 0}
                onClick={() => onPage(page - 1)}
              >
                <CaretLeft aria-hidden="true" />
              </button>
            </li>
            {pageWindow(page + 1, pages).map((entry, index, window) =>
              entry === '…' ? (
                <li key={`gap-after-${window[index - 1]}`} aria-hidden="true" className="drive-pagination__gap">
                  …
                </li>
              ) : (
                <li key={entry}>
                  <button
                    type="button"
                    className="drive-button drive-pagination__page"
                    aria-label={t('drive.pagination.page', { page: formatNumber(entry, locale) })}
                    aria-current={entry === page + 1 ? 'page' : undefined}
                    onClick={() => onPage(entry - 1)}
                  >
                    {formatNumber(entry, locale)}
                  </button>
                </li>
              ),
            )}
            <li>
              <button
                type="button"
                className="drive-button"
                aria-label={t('drive.pagination.next')}
                disabled={page >= pages - 1}
                onClick={() => onPage(page + 1)}
              >
                <CaretRight aria-hidden="true" />
              </button>
            </li>
          </ul>
        </nav>
      )}
      {showSizes && (
        <label className="drive-pagination__size">
          <span>{t('drive.pagination.sizeLabel')}</span>
          <select value={size} onChange={(event) => onSize(Number(event.target.value))}>
            {sizes.map((option) => (
              <option key={option} value={option}>
                {formatNumber(option, locale)}
              </option>
            ))}
          </select>
        </label>
      )}
    </div>
  );
}
