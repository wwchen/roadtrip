import type { Preview } from '@storybook/react-vite';
import { IconSprite } from '@ui';
import { DARK_MODE_CLASS } from '@/lib/theme';
import '@ui/styles.css';
import '../src/ui/storybook.css';

const LIGHT_MODE = 'light';
const ZION_THEME_CLASS = 'theme-roadtrip-zion';

const preview: Preview = {
  decorators: [
    (Story, { parameters }) => {
      const root = document.documentElement;
      root.classList.add(ZION_THEME_CLASS);
      root.classList.toggle(DARK_MODE_CLASS, parameters.colorMode !== LIGHT_MODE);
      return (
        <div className="rt-storybook-shell">
          <IconSprite all />
          <Story />
        </div>
      );
    },
  ],
  parameters: {
    a11y: {
      test: 'error',
    },
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    layout: 'fullscreen',
  },
  tags: ['autodocs'],
};

export default preview;
