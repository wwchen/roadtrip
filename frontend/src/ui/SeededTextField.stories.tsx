import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { SeededTextField, Toggle } from '@ui';

const checkedOf = (e: Event): boolean => (e.target as HTMLInputElement).checked;
const valueOf = (e: Event): string => (e.target as HTMLInputElement).value;

const meta = {
  title: 'Ui/SeededTextField',
  parameters: {
    docs: {
      description: {
        component:
          'A `TextField` that reads `seed` once, at its own mount, and ignores it ' +
          'thereafter — the fix for LDS’s uncontrolled inputs when a parent unmounts and ' +
          'remounts the field (one gated on a toggle, say) rather than keeping it mounted ' +
          "for its whole life. See the component's own doc comment for why `defaultValue` " +
          'alone is not enough.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** A value already on file, shown on mount. */
export const Seeded: Story = {
  render: () => <SeededTextField id="story-seeded" label="Slack channel" seed="#trip-alerts" />,
};

/** Nothing on file yet: seeded with an empty string. */
export const Empty: Story = {
  render: () => <SeededTextField id="story-empty" label="Slack channel" seed="" />,
};

/**
 * The case the component exists for: a field its parent unmounts and remounts, the
 * same shape as `TriggerSelector`'s channel field toggling with Slack. Type into the
 * field, then hide and show it again — the remounted field seeds from the mirrored
 * value typed a moment ago, not from the value the story first mounted with.
 */
export const RemountsWithTheCurrentValue: Story = {
  render: () => <RemountingField />,
};

function RemountingField() {
  const [shown, setShown] = useState(true);
  const [value, setValue] = useState('#trip-alerts');

  return (
    <div className="rt-storybook-stack">
      <Toggle
        id="story-remount-toggle"
        label="Notify Slack"
        aria-label="Notify Slack"
        checked={shown}
        onChange={(e) => setShown(checkedOf(e))}
      />
      {shown ? (
        <SeededTextField
          id="story-remount-field"
          label="Channel"
          seed={value}
          onChange={(e) => setValue(valueOf(e))}
        />
      ) : null}
      <p className="rt-storybook-note">Mirrored value: {value || '(empty)'}</p>
    </div>
  );
}
