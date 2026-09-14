import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { GroupSizeStepper } from './GroupSizeStepper';
import './topbar.css';

const meta = {
  title: 'Trip/GroupSizeStepper',
  parameters: {
    docs: { description: { component: 'Group size for the campground filter. Off until the first step; below the minimum it turns off again.' } },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function Live({ initial }: { initial: number | null }) {
  const [value, setValue] = useState<number | null>(initial);
  return <GroupSizeStepper value={value} onChange={setValue} />;
}

export const Off: Story = { render: () => <Live initial={null} /> };
export const FourPeople: Story = { render: () => <Live initial={4} /> };
