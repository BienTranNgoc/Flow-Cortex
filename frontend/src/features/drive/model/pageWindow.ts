/**
 * The page buttons to show for `current` of `total` pages, both 1-based: first, last and the
 * neighbours of the current page, with `'…'` standing for two or more hidden pages.
 */
export function pageWindow(current: number, total: number): (number | '…')[] {
  if (total < 1) {
    return [];
  }
  const shown = new Set([1, total, current - 1, current, current + 1]);
  const pages = [...shown].filter((page) => page >= 1 && page <= total).sort((a, b) => a - b);
  const window: (number | '…')[] = [];
  let previous = 0;
  for (const page of pages) {
    if (page - previous === 2) {
      window.push(previous + 1);
    } else if (page - previous > 2) {
      window.push('…');
    }
    window.push(page);
    previous = page;
  }
  return window;
}

/** The 0-based page to show when `page` is past the end of `total` entries of `size` per page. */
export function pageAfterShrink(page: number, total: number, size: number): number {
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);
  return page > lastPage ? lastPage : page;
}
