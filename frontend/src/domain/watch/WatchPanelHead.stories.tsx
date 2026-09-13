import type { Meta, StoryObj } from '@storybook/react-vite';
import { WatchPanelHead } from './WatchPanelHead';
import './watch-editor.css';

const meta = {
  title: 'Domain/WatchPanelHead',
  parameters: {
    docs: {
      description: {
        component:
          'The title block and close affordance both watch panels (the editor and the ' +
          'sign-in gate) share: a campground name, the date window the watch covers, and ' +
          'an optional close button.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** A named campground with its date window, and a close button. */
export const NamedWithDateWindow: Story = {
  render: () => (
    <WatchPanelHead title="Watch Bowman Bay" subtitle="Tuesday, August 11" onClose={() => {}} />
  ),
};

/** A multi-night window. */
export const DateRange: Story = {
  render: () => (
    <WatchPanelHead
      title="Watch Fallen Leaf"
      subtitle="Friday, August 14 – Sunday, August 16"
      onClose={() => {}}
    />
  ),
};

/** No `onClose`: the panel that hosts this head owns dismissal some other way. */
export const NoCloseButton: Story = {
  render: () => <WatchPanelHead title="Watch Bowman Bay" subtitle="Tuesday, August 11" />,
};

/** A title with no subtitle yet — the watch has a target but no date chosen. */
export const TitleOnly: Story = {
  render: () => <WatchPanelHead title="Watch Bowman Bay" onClose={() => {}} />,
};
