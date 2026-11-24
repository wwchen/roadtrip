import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { storyTabRoute } from './story-fixtures';
import { TabNav } from './TabNav';
import type { DashboardTab } from './useTabRoute';
import './dashboard.css';

function Tabs({ initial }: { initial: DashboardTab }) {
  const [tab, setTab] = useState<DashboardTab>(initial);
  return <TabNav route={{ ...storyTabRoute(tab), goToTab: setTab }} />;
}

const meta = {
  title: 'Availability dashboard/TabNav',
  parameters: {
    docs: {
      description: {
        component:
          'The dashboard’s tabs, as real links: each has its own address, the active one carries ' +
          '`aria-current="page"`, and a plain click switches tabs in place.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Pollers: Story = { render: () => <Tabs initial="pollers" /> };

export const Changes: Story = { render: () => <Tabs initial="changes" /> };
