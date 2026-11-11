import {
  buildTree,
  missingViewFeatures,
  setFeature,
  setModule,
  toggleAction,
  visibleCatalog,
  type CatalogItem,
} from './permissionSet';

const entry = (
  module: string,
  feature: string,
  action: string,
  superAdminOnly = false,
): CatalogItem => ({
  key: `${module}.${feature}.${action}`,
  module,
  feature,
  action,
  superAdminOnly,
});

const CATALOG: CatalogItem[] = [
  entry('support', 'ticket', 'reply'),
  entry('support', 'ticket', 'view'),
  entry('support', 'ticket', 'close'),
  entry('content', 'help_topic', 'view'),
  entry('content', 'help_topic', 'manage'),
  entry('content', 'help_article', 'view'),
  entry('content', 'help_article', 'manage'),
  entry('workflow', 'workflow', 'manage'),
  entry('system', 'role', 'view', true),
  entry('system', 'role', 'manage', true),
];

const keys = (...list: string[]) => new Set(list);
const sorted = (set: ReadonlySet<string>) => [...set].sort();

describe('toggleAction', () => {
  it('adds the feature view with a non-view action', () => {
    const next = toggleAction(CATALOG, keys(), 'support.ticket.reply', true);
    expect(sorted(next)).toEqual(['support.ticket.reply', 'support.ticket.view']);
  });

  it('clears the whole feature when view is unticked', () => {
    const draft = keys('support.ticket.view', 'support.ticket.reply', 'support.ticket.close');
    expect(toggleAction(CATALOG, draft, 'support.ticket.view', false).size).toBe(0);
  });

  it('keeps view when a non-view action is unticked', () => {
    const draft = keys('support.ticket.view', 'support.ticket.reply');
    expect(sorted(toggleAction(CATALOG, draft, 'support.ticket.reply', false))).toEqual([
      'support.ticket.view',
    ]);
  });

  it('toggles alone in a feature that has no view action', () => {
    const on = toggleAction(CATALOG, keys(), 'workflow.workflow.manage', true);
    expect(sorted(on)).toEqual(['workflow.workflow.manage']);
    expect(toggleAction(CATALOG, on, 'workflow.workflow.manage', false).size).toBe(0);
  });

  it('does not mutate the draft it was given', () => {
    const draft = keys('support.ticket.view');
    toggleAction(CATALOG, draft, 'support.ticket.reply', true);
    expect(sorted(draft)).toEqual(['support.ticket.view']);
  });

  it('leaves a key the catalog does not know untouched', () => {
    const draft = keys('billing.refund.approve', 'support.ticket.view');
    const next = toggleAction(CATALOG, draft, 'support.ticket.view', false);
    expect(sorted(next)).toEqual(['billing.refund.approve']);
  });
});

describe('setFeature and setModule', () => {
  it('ticks and unticks every action of a feature', () => {
    const on = setFeature(CATALOG, keys(), 'support', 'ticket', true);
    expect(sorted(on)).toEqual([
      'support.ticket.close',
      'support.ticket.reply',
      'support.ticket.view',
    ]);
    expect(setFeature(CATALOG, on, 'support', 'ticket', false).size).toBe(0);
  });

  it('ticks and unticks every action of a module without touching another module', () => {
    const on = setModule(CATALOG, keys('support.ticket.view'), 'content', true);
    expect(sorted(on)).toEqual([
      'content.help_article.manage',
      'content.help_article.view',
      'content.help_topic.manage',
      'content.help_topic.view',
      'support.ticket.view',
    ]);
    expect(sorted(setModule(CATALOG, on, 'content', false))).toEqual(['support.ticket.view']);
  });
});

describe('buildTree', () => {
  it('lists view first and computes on, partial and off per feature and module', () => {
    const tree = buildTree(CATALOG, keys('content.help_topic.view'));
    const content = tree.find((module) => module.id === 'content');
    expect(content?.state).toBe('partial');
    expect(content?.features.map((feature) => [feature.id, feature.state])).toEqual([
      ['help_topic', 'partial'],
      ['help_article', 'off'],
    ]);
    const ticket = tree.find((module) => module.id === 'support')?.features[0];
    expect(ticket?.actions.map((action) => action.action)).toEqual(['view', 'reply', 'close']);
    expect(ticket?.state).toBe('off');
  });

  it('marks a fully ticked feature and module as on, with counts', () => {
    const tree = buildTree(
      CATALOG,
      keys('support.ticket.view', 'support.ticket.reply', 'support.ticket.close'),
    );
    const support = tree.find((module) => module.id === 'support');
    expect(support?.state).toBe('on');
    expect(support?.selected).toBe(3);
    expect(support?.total).toBe(3);
  });

  it('keeps modules and features in catalog order', () => {
    expect(buildTree(CATALOG, keys()).map((module) => module.id)).toEqual([
      'support',
      'content',
      'workflow',
      'system',
    ]);
  });
});

describe('visibleCatalog', () => {
  it('hides super-admin-only keys unless the role is the super admin', () => {
    expect(visibleCatalog(CATALOG, false).some((item) => item.superAdminOnly)).toBe(false);
    expect(visibleCatalog(CATALOG, true)).toHaveLength(CATALOG.length);
  });
});

describe('missingViewFeatures', () => {
  it('names a feature holding an action without its view', () => {
    expect(
      missingViewFeatures(CATALOG, keys('support.ticket.reply', 'content.help_article.view')),
    ).toEqual(['support.ticket']);
  });

  it('accepts a feature without a view action', () => {
    expect(missingViewFeatures(CATALOG, keys('workflow.workflow.manage'))).toEqual([]);
  });

  it('agrees with every draft the cascade produces', () => {
    let draft: ReadonlySet<string> = keys();
    for (const item of CATALOG.filter((candidate) => !candidate.superAdminOnly)) {
      draft = toggleAction(CATALOG, draft, item.key, true);
      expect(missingViewFeatures(CATALOG, draft)).toEqual([]);
    }
    draft = toggleAction(CATALOG, draft, 'content.help_topic.view', false);
    expect(missingViewFeatures(CATALOG, draft)).toEqual([]);
  });
});
