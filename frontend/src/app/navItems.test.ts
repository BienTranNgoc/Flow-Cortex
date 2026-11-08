import { BookOpen, FlowArrow, House, PlugsConnected, Play } from '@phosphor-icons/react';
import { DOCS_ICON, primaryNavItems } from './navItems';

const labels = () => primaryNavItems().map((item) => item.label);

describe('primaryNavItems', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('lists only the core pages while the demo pages flag is off', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');
    expect(labels()).toEqual(['Home', 'Workflows']);
  });

  it('adds Runs and Integrations once the flag turns on, without re-importing the module', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');
    expect(labels()).toEqual(['Home', 'Workflows']);

    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    expect(labels()).toEqual(['Home', 'Workflows', 'Runs', 'Integrations']);
  });

  it('gives every entry its icon, and Docs the shared docs icon', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');

    expect(primaryNavItems().map((item) => [item.label, item.icon])).toEqual([
      ['Home', House],
      ['Workflows', FlowArrow],
      ['Runs', Play],
      ['Integrations', PlugsConnected],
    ]);
    expect(DOCS_ICON).toBe(BookOpen);
  });
});
