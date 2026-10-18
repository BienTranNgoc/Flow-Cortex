import { createUseTheme } from '@flow-cortex/ui-kit';

export type { Theme } from '@flow-cortex/ui-kit';

const { initTheme, useTheme } = createUseTheme({
  storageKey: 'flow-cortex:theme',
  systemDefault: 'light',
});

export { initTheme, useTheme };
