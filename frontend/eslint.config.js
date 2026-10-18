import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import prettier from 'eslint-config-prettier';
import i18next from 'eslint-plugin-i18next';
import i18nextDefaults from 'eslint-plugin-i18next/lib/options/defaults.js';

export default tseslint.config(
  {
    ignores: ['dist', 'coverage'],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        ...globals.browser,
        ...globals.es2021,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': [
        'warn',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
    },
  },
  {
    files: ['**/*.{test,spec}.{ts,tsx}', 'src/test/**'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    ...i18next.configs['flat/recommended'],
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/**/*.{test,spec}.{ts,tsx}', 'src/test/**'],
    rules: {
      'i18next/no-literal-string': [
        'error',
        {
          mode: 'jsx-only',
          'jsx-attributes': { include: ['aria-label', 'placeholder', 'title', 'alt'] },
          // The rule replaces an option's default list rather than extending it.
          words: {
            exclude: [
              ...i18nextDefaults.words.exclude,
              'FlowCortex',
              'FLOWCORTEX AI 2\\.5',
            ],
          },
          // react-hook-form field names
          callees: { exclude: [...i18nextDefaults.callees.exclude, 'register'] },
        },
      ],
    },
  },
  prettier,
);
