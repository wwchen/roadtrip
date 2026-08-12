import type { Preview } from '@storybook/react-vite';
import '@ui/styles.css';
import '../src/ui/storybook.css';

const preview: Preview = {
  decorators: [
    // `theme-roadtrip-zion`, not the pre-#601 `theme-roadtrip`: that older name
    // matches no rule in roadtrip-zion.css, so this decorator was inert and the
    // WHOLE catalog rendered with no theme tokens at all — `mode-dark` included,
    // since the night block is scoped `.mode-dark.theme-roadtrip-zion`.
    (Story) => (
      <div className="theme-roadtrip-zion mode-dark rt-storybook-shell">
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
