import type { UsageDayResponse } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';

const BAR_WIDTH = 10;
const BAR_GAP = 4;
const CHART_HEIGHT = 96;

interface UsageBarsProps {
  readonly series: readonly UsageDayResponse[];
  readonly showCredits: boolean;
}

/** Day-of-month dates arrive as `YYYY-MM-DD`; read as UTC so the shown day never shifts with the browser zone. */
function dayLabel(day: string): string {
  return formatDateTime(`${day}T00:00:00Z`, activeLocale(), {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

/**
 * Runs per day as inline SVG bars (the failed share in red) with the same numbers in a table
 * only assistive technology reads.
 */
export function UsageBars({ series, showCredits }: Readonly<UsageBarsProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const peak = Math.max(1, ...series.map((point) => point.runs));
  const width = Math.max(1, series.length) * (BAR_WIDTH + BAR_GAP) - BAR_GAP;
  return (
    <div className="usage-bars">
      <div className="usage-bars__scroll">
        <svg
          className="usage-bars__chart"
          viewBox={`0 0 ${width} ${CHART_HEIGHT}`}
          preserveAspectRatio="none"
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={t('runHistory.usage.chartLabel')}
        >
          {series.map((point, index) => {
            const total = (point.runs / peak) * CHART_HEIGHT;
            const failed = (point.failed / peak) * CHART_HEIGHT;
            const x = index * (BAR_WIDTH + BAR_GAP);
            return (
              <g key={point.day}>
                <title>{`${dayLabel(point.day)}: ${formatNumber(point.runs, locale)}`}</title>
                <rect
                  className="usage-bars__track"
                  x={x}
                  y={0}
                  width={BAR_WIDTH}
                  height={CHART_HEIGHT}
                  rx={2}
                />
                <rect
                  className="usage-bars__runs"
                  x={x}
                  y={CHART_HEIGHT - total}
                  width={BAR_WIDTH}
                  height={total}
                  rx={2}
                />
                {point.failed > 0 && (
                  <rect
                    className="usage-bars__failed"
                    x={x}
                    y={CHART_HEIGHT - failed}
                    width={BAR_WIDTH}
                    height={failed}
                    rx={2}
                  />
                )}
              </g>
            );
          })}
        </svg>
      </div>
      <div className="visually-hidden">
        <table>
          <caption>{t('runHistory.usage.chartTable')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('runHistory.usage.day')}</th>
              <th scope="col">{t('runHistory.usage.runs')}</th>
              <th scope="col">{t('runHistory.usage.failedRuns')}</th>
              {showCredits && <th scope="col">{t('runHistory.usage.credits')}</th>}
            </tr>
          </thead>
          <tbody>
            {series.map((point) => (
              <tr key={point.day}>
                <th scope="row">{dayLabel(point.day)}</th>
                <td>{formatNumber(point.runs, locale)}</td>
                <td>{formatNumber(point.failed, locale)}</td>
                {showCredits && <td>{formatNumber(point.credits ?? 0, locale)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
