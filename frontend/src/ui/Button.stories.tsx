import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from '@ui';

const meta = {
  title: 'Design System/Primitives/Button',
  component: Button,
  args: {
    children: 'Plan a trip',
    variant: 'primary',
  },
  parameters: {
    docs: {
      description: {
        component: 'The LDS button exposed through the Roadtrip @ui boundary.',
      },
    },
  },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Primary: Story = {};

export const Secondary: Story = {
  args: {
    children: 'Save draft',
    variant: 'secondary',
  },
};

export const Disabled: Story = {
  args: {
    children: 'Saving…',
    disabled: true,
  },
};
