import { useId } from 'react';
import './barSeries.css';

export interface BarPoint {
  /** ISO calendar day `YYYY-MM-DD`. */
  day: string;
  runs: number;
  /** When present, drawn over the bar as the part of `runs` that failed. */
  failed?: number | undefined;
}

interface BarSeriesProps {
  points: readonly BarPoint[];
  /** The accessible name of the chart, stating the period total. */
  label: string;
  /** Names of the text table's columns; the failed column appears only when `failed` is given. */
  headers: { day: string; runs: string; failed: string };
  /** Formats a day cell of the text table. */
  formatDay: (day: string) => string;
}

const WIDTH = 600;
const HEIGHT = 120;
const GAP = 2;

/**
 * Daily bars drawn as inline SVG, scaled to the series maximum. The picture is one image for
 * assistive technology; every number is also in a visually hidden table right after it.
 */
export function BarSeries({ points, label, headers, formatDay }: Readonly<BarSeriesProps>) {
  const tableId = useId();
  const max = Math.max(1, ...points.map((point) => point.runs));
  const slot = points.length > 0 ? WIDTH / points.length : WIDTH;
  const barWidth = Math.max(1, slot - GAP);
  const showFailed = points.some((point) => point.failed !== undefined);
  const heightOf = (count: number) => (count / max) * HEIGHT;

  return (
    <figure className="bar-series">
      <svg
        className="bar-series__chart"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={label}
        aria-describedby={tableId}
      >
        {points.map((point, index) => {
          const x = index * slot + GAP / 2;
          const runsHeight = heightOf(point.runs);
          const failedHeight = heightOf(Math.min(point.failed ?? 0, point.runs));
          return (
            <g key={point.day}>
              <title>{`${formatDay(point.day)}: ${point.runs}`}</title>
              {point.runs > 0 && (
                <rect
                  className="bar-series__runs"
                  x={x}
                  y={HEIGHT - runsHeight}
                  width={barWidth}
                  height={runsHeight}
                />
              )}
              {failedHeight > 0 && (
                <rect
                  className="bar-series__failed"
                  x={x}
                  y={HEIGHT - failedHeight}
                  width={barWidth}
                  height={failedHeight}
                />
              )}
            </g>
          );
        })}
      </svg>
      <table id={tableId} className="cortex-admin-visually-hidden">
        <caption>{label}</caption>
        <thead>
          <tr>
            <th scope="col">{headers.day}</th>
            <th scope="col">{headers.runs}</th>
            {showFailed && <th scope="col">{headers.failed}</th>}
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.day}>
              <th scope="row">{formatDay(point.day)}</th>
              <td>{point.runs}</td>
              {showFailed && <td>{point.failed ?? 0}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
