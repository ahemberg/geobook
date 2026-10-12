import { defineConfig, globalIgnores } from 'eslint/config';
import prettier from 'eslint-config-prettier/flat';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';

export default defineConfig([
  globalIgnores([
    'dist/**',
    'node/**',
    '.fallow/**',
    'coverage/**',
    'playwright-report/**',
    'test-results/**',
  ]),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      tseslint.configs.recommendedTypeChecked,
      reactHooks.configs.flat.recommended,
      jsxA11y.flatConfigs.recommended,
    ],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  },
  prettier,
]);
