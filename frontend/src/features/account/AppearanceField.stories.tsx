import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { DARK_MODE_CLASS, type ThemeChoice } from '@/lib/theme';
import { AppearanceField } from './AppearanceField';

/** The class the production shells put on `<html>`. Named here rather than
 *  applying `preview.tsx`'s wrapper, so the dark story renders correctly on
 *  its own tokens regardless of the global decorator's class. */
const ZION_THEME_CLASS = 'theme-roadtrip-zion';

// `component` is omitted: both `value` and `onChange` are required with no
// meta-level default, and every story here supplies its own local state
// through `render` rather than `args` — matching `Components.stories.tsx`'s
// render-only catalog entries, and sidestepping a CSF3 typing quirk where a
// `component` with no defaulted required props makes `args` mandatory on
// every story even when `render` is what actually supplies the props.
const meta = {
  title: 'Account/AppearanceField',
  parameters: {
    docs: {
      description: {
        component:
          'The theme picker on the account settings panel. A three-way ' +
          '`SegmentedControl` — light, dark, system — with a plain-markup ' +
          'label and help text, since `SegmentedControl` renders neither.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Local state rather than the real store, so opening the story does not
 *  repaint Storybook itself. */
function Demo({ initial }: { initial: ThemeChoice }) {
  const [value, setValue] = useState<ThemeChoice>(initial);
  return <AppearanceField value={value} onChange={setValue} />;
}

export const System: Story = { render: () => <Demo initial="system" /> };
export const Light: Story = { render: () => <Demo initial="light" /> };
export const Dark: Story = { render: () => <Demo initial="dark" /> };

/** The same control under the night palette, which is where the basalt selection
 *  and the muted segments have to be checked against each other. Wraps in the
 *  real theme class plus `mode-dark` — the same pair `applyMode` puts on
 *  `<html>` in production — rather than relying on the global decorator. */
export const InDarkMode: Story = {
  render: () => (
    <div className={`${ZION_THEME_CLASS} ${DARK_MODE_CLASS}`} style={{ background: 'var(--surface-page)', padding: 24 }}>
      <Demo initial="dark" />
    </div>
  ),
};
