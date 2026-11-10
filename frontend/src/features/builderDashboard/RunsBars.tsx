import { useTranslation } from 'react-i18next';
import type { DayCountResponse } from '@flow-cortex/api-client';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';

const BAR_WIDTH = 10;
const BAR_GAP = 2;
const HEIGHT = 100;

interface RunsBarsProps {
  readonly series: readonly DayCountResponse[];
}

/** `day` is a calendar date with no time; format it in UTC so no browser zone shifts it. */
function dayLabel(day: string): string {
  return formatDateTime(`${day}T00:00:00Z`, activeLocale(), { dateStyle: 'medium', timeZone: 'UTC' });
}

/**
 * One bar per day scaled to the busiest day, with the failed share overlaid. The picture is
 * summarised by its label and every number is also in a table for readers who cannot see it.
 */
export function RunsBars({ series }: Readonly<RunsBarsProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const peak = Math.max(1, ...series.map((point) => point.runs));
  const total = series.reduce((sum, point) => sum + point.runs, 0);
  const width = series.length * (BAR_WIDTH + BAR_GAP) - BAR_GAP;

  return (
    <div className="runs-bars">
      <svg
        className="runs-bars__svg"
        viewBox={`0 0 ${Math.max(width, BAR_WIDTH)} ${HEIGHT}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={t('workflow.builderDashboard.chart.label', {
          count: total,
          days: series.length,
          n: formatNumber(total, locale),
        })}
      >
        {series.map((point, index) => {
          const x = index * (BAR_WIDTH + BAR_GAP);
          const runsHeight = (point.runs / peak) * HEIGHT;
          const failedHeight = (Math.min(point.failed, point.runs) / peak) * HEIGHT;
          return (
            <g key={point.day}>
              <rect
                className="runs-bars__bar"
                x={x}
                y={HEIGHT - runsHeight}
                width={BAR_WIDTH}
                height={runsHeight}
              />
              {failedHeight > 0 && (
                <rect
                  className="runs-bars__failed"
                  x={x}
                  y={HEIGHT - failedHeight}
                  width={BAR_WIDTH}
                  height={failedHeight}
                />
              )}
            </g>
          );
        })}
      </svg>
      <table className="visually-hidden">
        <caption>{t('workflow.builderDashboard.chart.tableLabel')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('workflow.builderDashboard.chart.day')}</th>
            <th scope="col">{t('workflow.builderDashboard.chart.runs')}</th>
            <th scope="col">{t('workflow.builderDashboard.chart.failed')}</th>
          </tr>
        </thead>
        <tbody>
          {series.map((point) => (
            <tr key={point.day}>
              <th scope="row">{dayLabel(point.day)}</th>
              <td>{formatNumber(point.runs, locale)}</td>
              <td>{formatNumber(point.failed, locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
