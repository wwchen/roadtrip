import type { Meta, StoryObj } from '@storybook/react-vite';
import { PageErrorBoundary } from './PageErrorBoundary';

class TrippedBoundary extends PageErrorBoundary {
  state = { failed: true };
}

const meta = {
  title: 'App/PageErrorBoundary',
  parameters: {
    docs: {
      description: {
        component:
          'What a page shows instead of a blank document when a component throws while ' +
          'rendering: one banner, with a reload. The story draws the boundary already tripped, ' +
          'so the catalog shows the fallback without throwing.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Fallback: Story = {
  render: () => (
    <TrippedBoundary>
      <p>Never rendered.</p>
    </TrippedBoundary>
  ),
};

export const Healthy: Story = {
  render: () => (
    <PageErrorBoundary>
      <p>A page that rendered normally: the boundary adds nothing.</p>
    </PageErrorBoundary>
  ),
};
