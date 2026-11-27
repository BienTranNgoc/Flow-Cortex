import type { CategoryFilter } from './FilterBar';

const SEARCH_TERMS = [
  'cv',
  'cv checker',
  'kiểm tra cv',
  'resume',
  'pdf',
  'ats',
  'star',
  'hồ sơ',
  'việc làm',
  'jobs',
];

export function matchesCvCheckCard(query: string, category: CategoryFilter): boolean {
  if (category !== 'all' && category !== 'production') return false;
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  return SEARCH_TERMS.some((term) => term.includes(needle));
}
