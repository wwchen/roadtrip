import type { Meta, StoryObj } from '@storybook/react-vite';
import { DateWindowFields } from './DateWindowFields';
import './topbar.css';

const meta = {
  title: 'Trip/DateWindowFields',
  parameters: {
    docs: {
      description: {
        component:
          'The Check availability step\'s date entry. Plain text fields with ISO-only ' +
          'validation, not `type="date"` — see the file comment for why.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
  render: () => <DateWindowFields initial={null} onSave={() => {}} onCancel={() => {}} />,
};

export const Prefilled: Story = {
  render: () => (
    <DateWindowFields
      initial={{ start: '2026-09-11', end: '2026-09-13' }}
      onSave={() => {}}
      onCancel={() => {}}
    />
  ),
};
