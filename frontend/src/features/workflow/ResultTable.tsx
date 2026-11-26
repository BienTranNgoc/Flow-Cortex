import { ArrowDown, ArrowUp } from '@phosphor-icons/react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import {
  sortRows,
  visibleRows,
  type SortDirection,
  type TableCell,
  type TableRow,
} from './runResultModel';

interface Sort {
  column: string;
  direction: SortDirection;
}

const ARIA_SORT = { asc: 'ascending', desc: 'descending' } as const;

function cellText(cell: TableCell | undefined): string {
  return cell === null || cell === undefined ? '' : String(cell);
}

/** The next press on a column: ascending, then descending, then the output's own order. */
function nextSort(current: Sort | null, column: string): Sort | null {
  if (current?.column !== column) {
    return { column, direction: 'asc' };
  }
  return current.direction === 'asc' ? { column, direction: 'desc' } : null;
}

interface ResultTableProps {
  rows: readonly TableRow[];
  columns: readonly string[];
}

/** A sortable table of the output's rows; at most the first rows of the sort are drawn. */
export function ResultTable({ rows, columns }: Readonly<ResultTableProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [sort, setSort] = useState<Sort | null>(null);
  const collator = useMemo(() => new Intl.Collator(locale, { numeric: true }), [locale]);
  const shown = useMemo(
    () => visibleRows(sortRows(rows, sort?.column ?? '', sort?.direction ?? null, collator)),
    [rows, sort, collator],
  );

  return (
    <div className="result-table-view">
      <p className="result-summary">
        {t('workflow.result.tableSummary', {
          rows: formatNumber(shown.total, locale),
          cols: formatNumber(columns.length, locale),
        })}
      </p>
      <div
        role="region"
        aria-label={t('workflow.result.tableRegion')}
        tabIndex={0}
        className="result-table-wrap"
      >
        <table className="result-table">
          <thead>
            <tr>
              {columns.map((column) => {
                const direction = sort?.column === column ? sort.direction : null;
                return (
                  <th
                    key={column}
                    scope="col"
                    {...(direction ? { 'aria-sort': ARIA_SORT[direction] } : {})}
                  >
                    <button
                      type="button"
                      className="result-sort"
                      aria-label={t('workflow.result.sortBy', { column })}
                      onClick={() => setSort(nextSort(sort, column))}
                    >
                      <span>{column}</span>
                      {direction === 'asc' && <ArrowUp size={12} aria-hidden="true" />}
                      {direction === 'desc' && <ArrowDown size={12} aria-hidden="true" />}
                    </button>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {shown.rows.map(({ index, row }) => (
              <tr key={index}>
                {columns.map((column) => (
                  <td key={column}>{cellText(row[column])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {shown.total > shown.rows.length && (
        <p className="result-note">
          {t('workflow.result.tableLimited', {
            shown: formatNumber(shown.rows.length, locale),
            total: formatNumber(shown.total, locale),
          })}
        </p>
      )}
    </div>
  );
}
