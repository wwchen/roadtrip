import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { Watch } from '@/api/watches-api';
import { triggerStateOf, type TriggerState } from '@/lib/watch-triggers';
import { WatchForm } from './WatchForm';
import { TriggerSelector } from './TriggerSelector';
import './watches.css';

function watch(over: Partial<Watch> & Pick<Watch, 'id' | 'status'>): Watch {
  return {
    targets: [],
    campsite_filters: {},
    start_date: '2026-08-14',
    end_date: '2026-08-16',
    trigger_kinds: ['slack_notify'],
    trigger_config: {},
    stop_when_triggered: true,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...over,
  };
}

const EDITED_WATCH: Watch = watch({
  id: 601,
  poi_id: 42,
  status: 'active',
  start_date: '2026-09-20',
  end_date: '2026-09-22',
  trigger_kinds: ['slack_notify', 'email_notify'],
  trigger_config: { slack_notify: { channel: '#trip-alerts' } },
});

const meta = {
  title: 'Watches/WatchForm',
  parameters: {
    docs: {
      description: {
        component:
          'The create/edit form behind "New watch" and each row’s Edit button, and ' +
          '`TriggerSelector`, the trigger-kind block it hosts (also used by ' +
          '`WatchEditor` in the availability grid).',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** A blank form, optionally prefilled from a deep link (`?poi_id=…&start_date=…`). */
export const Create: Story = {
  render: () => <WatchForm mode="create" prefill={{ poi_id: '42', start_date: '2026-09-20' }} onSubmit={() => {}} />,
};

/** Editing an existing watch: the target fields lock, only the dates and triggers change. */
export const Edit: Story = {
  render: () => <WatchForm mode="edit" watch={EDITED_WATCH} onSubmit={() => {}} onCancel={() => {}} />,
};

/** A save in flight: the buttons disable, the typed values stay. */
export const Saving: Story = {
  render: () => <WatchForm mode="edit" watch={EDITED_WATCH} loading onSubmit={() => {}} onCancel={() => {}} />,
};

/** The backend rejected the save. */
export const SaveFailed: Story = {
  render: () => (
    <WatchForm
      mode="edit"
      watch={EDITED_WATCH}
      error="That date range is no longer available to watch."
      onSubmit={() => {}}
      onCancel={() => {}}
    />
  ),
};

/** Every trigger off: a new watch with nothing configured yet. */
export const TriggerSelectorEmpty: Story = {
  render: () => <TriggerSelectorDemo initial={{ slackNotify: false, slackChannel: '', emailNotify: false, addToCart: false, stopWhenTriggered: false }} />,
};

/** Slack on with a saved channel, email also on. */
export const TriggerSelectorConfigured: Story = {
  render: () => <TriggerSelectorDemo initial={triggerStateOf(EDITED_WATCH)} />,
};

function TriggerSelectorDemo({ initial }: { initial: TriggerState }) {
  const [value, setValue] = useState(initial);
  return <TriggerSelector value={value} onChange={setValue} />;
}
