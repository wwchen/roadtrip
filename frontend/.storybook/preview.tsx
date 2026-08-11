import type { Preview } from '@storybook/react-vite';
import '@ui/styles.css';
import '../src/ui/storybook.css';

const preview: Preview = {
  decorators: [
    (Story) => (
      <div className="theme-roadtrip mode-dark rt-storybook-shell">
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
