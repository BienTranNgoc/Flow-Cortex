import {
  BookOpen,
  ChatsCircle,
  FlowArrow,
  House,
  PlugsConnected,
  Play,
} from '@phosphor-icons/react';
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

  it('adds Chat after Workflows only while the chat switch is on', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    const withChat = primaryNavItems(undefined, { chat: true });
    expect(withChat.map((item) => item.label)).toEqual([
      'Home',
      'Workflows',
      'Chat',
      'Runs',
      'Integrations',
    ]);
    expect(withChat.find((item) => item.label === 'Chat')?.to).toBe('/chat');
    expect(withChat.find((item) => item.label === 'Chat')?.icon).toBe(ChatsCircle);
    expect(primaryNavItems(undefined, { chat: false }).map((item) => item.label)).not.toContain(
      'Chat',
    );
  });
});
