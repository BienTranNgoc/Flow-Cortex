interface Identified {
  readonly workflow: { readonly id?: string | undefined };
}

/**
 * The pages read so far as one list. Offset pages over ranks that move between refreshes can
 * repeat a workflow, so the first occurrence keeps its place and later ones are dropped.
 */
export function mergePages<T extends Identified>(
  pages: ReadonlyArray<{ readonly data: readonly T[] }>,
): T[] {
  const seen = new Set<string>();
  const merged: T[] = [];
  for (const { data } of pages) {
    for (const item of data) {
      const id = item.workflow.id;
      if (id !== undefined) {
        if (seen.has(id)) continue;
        seen.add(id);
      }
      merged.push(item);
    }
  }
  return merged;
}
