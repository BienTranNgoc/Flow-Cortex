/** The slice of a catalog entry the cascade needs; the API's `PermissionCatalogEntry` satisfies it. */
export interface CatalogItem {
  readonly key: string;
  readonly module: string;
  readonly feature: string;
  readonly action: string;
  readonly superAdminOnly: boolean;
}

export type CheckState = 'on' | 'partial' | 'off';

export interface ActionNode {
  readonly key: string;
  readonly action: string;
  readonly checked: boolean;
  readonly superAdminOnly: boolean;
}

export interface FeatureNode {
  readonly id: string;
  readonly module: string;
  readonly state: CheckState;
  readonly selected: number;
  readonly total: number;
  readonly actions: readonly ActionNode[];
}

export interface ModuleNode {
  readonly id: string;
  readonly state: CheckState;
  readonly selected: number;
  readonly total: number;
  readonly features: readonly FeatureNode[];
}

const VIEW = 'view';

/** The id a feature carries in the server's `PERMISSION_SET_INCONSISTENT` problem: `<module>.<feature>`. */
export function featureId(item: Pick<CatalogItem, 'module' | 'feature'>): string {
  return `${item.module}.${item.feature}`;
}

function stateOf(selected: number, total: number): CheckState {
  if (selected === 0) return 'off';
  return selected === total ? 'on' : 'partial';
}

/** Only the super admin role shows (and holds) super-admin-only keys. */
export function visibleCatalog<T extends CatalogItem>(
  catalog: readonly T[],
  includeSuperAdminOnly: boolean,
): T[] {
  return includeSuperAdminOnly ? [...catalog] : catalog.filter((item) => !item.superAdminOnly);
}

function featureItems(catalog: readonly CatalogItem[], module: string, feature: string) {
  return catalog.filter((item) => item.module === module && item.feature === feature);
}

/** Module › feature › action tree in catalog order, `view` first within a feature. */
export function buildTree(
  catalog: readonly CatalogItem[],
  keys: ReadonlySet<string>,
): ModuleNode[] {
  const modules = new Map<string, Map<string, CatalogItem[]>>();
  for (const item of catalog) {
    const features = modules.get(item.module) ?? new Map<string, CatalogItem[]>();
    const items = features.get(item.feature) ?? [];
    items.push(item);
    features.set(item.feature, items);
    modules.set(item.module, features);
  }

  return [...modules].map(([moduleId, features]) => {
    const featureNodes = [...features].map(([id, items]): FeatureNode => {
      const ordered = [...items].sort(
        (left, right) => Number(right.action === VIEW) - Number(left.action === VIEW),
      );
      const actions = ordered.map((item) => ({
        key: item.key,
        action: item.action,
        checked: keys.has(item.key),
        superAdminOnly: item.superAdminOnly,
      }));
      const selected = actions.filter((action) => action.checked).length;
      return {
        id,
        module: moduleId,
        state: stateOf(selected, actions.length),
        selected,
        total: actions.length,
        actions,
      };
    });
    const selected = featureNodes.reduce((sum, feature) => sum + feature.selected, 0);
    const total = featureNodes.reduce((sum, feature) => sum + feature.total, 0);
    return {
      id: moduleId,
      state: stateOf(selected, total),
      selected,
      total,
      features: featureNodes,
    };
  });
}

/**
 * Ticks or unticks one key keeping the draft valid: a non-view action brings its feature's `view`
 * when the feature has one, and unticking `view` clears every action of that feature.
 */
export function toggleAction(
  catalog: readonly CatalogItem[],
  keys: ReadonlySet<string>,
  key: string,
  on: boolean,
): Set<string> {
  const next = new Set(keys);
  const item = catalog.find((candidate) => candidate.key === key);
  if (!item) {
    return next;
  }
  const siblings = featureItems(catalog, item.module, item.feature);
  if (on) {
    next.add(key);
    const view = siblings.find((sibling) => sibling.action === VIEW);
    if (view) next.add(view.key);
  } else if (item.action === VIEW) {
    for (const sibling of siblings) next.delete(sibling.key);
  } else {
    next.delete(key);
  }
  return next;
}

function setAll(
  keys: ReadonlySet<string>,
  items: readonly CatalogItem[],
  on: boolean,
): Set<string> {
  const next = new Set(keys);
  for (const item of items) {
    if (on) next.add(item.key);
    else next.delete(item.key);
  }
  return next;
}

export function setFeature(
  catalog: readonly CatalogItem[],
  keys: ReadonlySet<string>,
  module: string,
  feature: string,
  on: boolean,
): Set<string> {
  return setAll(keys, featureItems(catalog, module, feature), on);
}

export function setModule(
  catalog: readonly CatalogItem[],
  keys: ReadonlySet<string>,
  module: string,
  on: boolean,
): Set<string> {
  return setAll(
    keys,
    catalog.filter((item) => item.module === module),
    on,
  );
}

/**
 * The features that have a `view` action in the catalog and hold another action without it: the same
 * rule the server answers `PERMISSION_SET_INCONSISTENT` for, in catalog order.
 */
export function missingViewFeatures(
  catalog: readonly CatalogItem[],
  keys: ReadonlySet<string>,
): string[] {
  const missing: string[] = [];
  for (const item of catalog) {
    const id = featureId(item);
    if (missing.includes(id)) continue;
    const siblings = featureItems(catalog, item.module, item.feature);
    const view = siblings.find((sibling) => sibling.action === VIEW);
    if (!view || keys.has(view.key)) continue;
    if (siblings.some((sibling) => sibling.action !== VIEW && keys.has(sibling.key))) {
      missing.push(id);
    }
  }
  return missing;
}
