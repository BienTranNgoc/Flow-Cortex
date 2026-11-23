import {
  BookOpen,
  ChatsCircle,
  FlowArrow,
  HardDrives,
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

  it('lists Home, Workflows and Runs while the demo pages flag is off', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');
    expect(labels()).toEqual(['Home', 'Workflows', 'Runs']);
  });

  it('adds Integrations once the flag turns on, without re-importing the module', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');
    expect(labels()).toEqual(['Home', 'Workflows', 'Runs']);

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

  it('adds Chat after Runs only while the chat switch is on', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    const withChat = primaryNavItems(undefined, { chat: true });
    expect(withChat.map((item) => item.label)).toEqual([
      'Home',
      'Workflows',
      'Runs',
      'Chat',
      'Integrations',
    ]);
    expect(withChat.find((item) => item.label === 'Chat')?.to).toBe('/chat');
    expect(withChat.find((item) => item.label === 'Chat')?.icon).toBe(ChatsCircle);
    expect(primaryNavItems(undefined, { chat: false }).map((item) => item.label)).not.toContain(
      'Chat',
    );
  });

  it('adds Drive after Runs, and after Chat when both switches are on', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    const labelsOf = (options: { chat?: boolean; drive?: boolean }) =>
      primaryNavItems(undefined, options).map((item) => item.label);

    const both = labelsOf({ chat: true, drive: true });
    expect(both[both.indexOf('Chat') + 1]).toBe('Drive');

    const driveOnly = labelsOf({ drive: true });
    expect(driveOnly[driveOnly.indexOf('Runs') + 1]).toBe('Drive');
    expect(driveOnly.indexOf('Drive')).toBeLessThan(driveOnly.indexOf('Integrations'));
    expect(both.indexOf('Drive')).toBeLessThan(both.indexOf('Integrations'));

    expect(labelsOf({ drive: false })).not.toContain('Drive');
    expect(labelsOf({})).not.toContain('Drive');

    const drive = primaryNavItems(undefined, { drive: true }).find((item) => item.label === 'Drive');
    expect(drive?.to).toBe('/drive');
    expect(drive?.icon).toBe(HardDrives);
  });
});
