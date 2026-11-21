import type { Preview } from '@storybook/react-vite';
import { IconSprite } from '@ui';
import '@ui/styles.css';
import '../src/ui/storybook.css';

const LIGHT_MODE = 'light';
const LIGHT_SHELL = 'theme-roadtrip-zion rt-storybook-shell';
const DARK_SHELL = 'theme-roadtrip-zion mode-dark rt-storybook-shell';

const preview: Preview = {
  decorators: [
    // `theme-roadtrip-zion`, not the pre-#601 `theme-roadtrip`: that older name
    // matches no rule in roadtrip-zion.css, so this decorator was inert and the
    // WHOLE catalog rendered with no theme tokens at all — `mode-dark` included,
    // since the night block is scoped `.mode-dark.theme-roadtrip-zion`.
    (Story, { parameters }) => (
      <div className={parameters.colorMode === LIGHT_MODE ? LIGHT_SHELL : DARK_SHELL}>
        <IconSprite all />
        <Story />
      </div>
    ),
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
