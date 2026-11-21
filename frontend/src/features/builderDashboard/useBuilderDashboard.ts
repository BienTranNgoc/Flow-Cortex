import { useQuery } from '@tanstack/react-query';
import {
  authHeaders,
  getBuilderRunsChart,
  getBuilderSummary,
  unwrap,
  type GetBuilderRunsChartPeriod,
} from '@flow-cortex/api-client';

export function useBuilderSummaryQuery() {
  return useQuery({
    queryKey: ['builder-dashboard', 'summary'],
    queryFn: () => unwrap(getBuilderSummary(authHeaders())),
  });
}

/** One cache entry per period, so switching back to a period already seen shows it at once. */
export function useBuilderChartQuery(period: GetBuilderRunsChartPeriod) {
  return useQuery({
    queryKey: ['builder-dashboard', 'runs-chart', period],
    queryFn: () => unwrap(getBuilderRunsChart({ period }, authHeaders())),
  });
}
