import type { StorybookConfig } from '@storybook/react-vite';
import { fileURLToPath } from 'node:url';
import { mergeConfig } from 'vite';

const STORY_FILES = '**/*.stories.@(js|jsx|mjs|ts|tsx)';
const DOC_FILES = '**/*.mdx';
const NEXT_LINK = 'next/link';
const NEXT_LINK_STAND_IN = fileURLToPath(new URL('./NextLink.tsx', import.meta.url));

const config: StorybookConfig = {
  stories: [`../src/${DOC_FILES}`, `../src/${STORY_FILES}`, `../../apps/web/src/${STORY_FILES}`],
  addons: ['@storybook/addon-a11y', '@storybook/addon-docs'],
  framework: '@storybook/react-vite',
  docs: {
    autodocs: 'tag',
  },
  viteFinal: (vite) => mergeConfig(vite, { resolve: { alias: { [NEXT_LINK]: NEXT_LINK_STAND_IN } } }),
};

export default config;
