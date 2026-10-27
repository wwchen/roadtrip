import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Sheet, SheetHeader, SheetSection, type SheetDetent } from '@ui';

const PHONE_FRAME: CSSProperties = {
  position: 'relative',
  width: 390,
  height: 720,
  margin: '0 auto',
  overflow: 'hidden',
  border: '1px solid var(--rt-border)',
  borderRadius: 24,
  background: 'linear-gradient(160deg, var(--rt-fill-subtle), var(--rt-bg))',
};

const DESKTOP_FRAME: CSSProperties = { ...PHONE_FRAME, width: 960, height: 640, borderRadius: 12 };

const meta = {
  title: 'Ui/Sheet',
  parameters: {
    docs: {
      description: {
        component:
          'A bottom sheet with three heights, controlled by its parent. Each `SheetSection` ' +
          'declares the height at which its content first appears and is absent below it, so ' +
          'a fact belongs to exactly one height; `from` is required, so a section with no ' +
          'declared height does not compile. `SheetHeader` shows its full form at peek and ' +
          'its compact form from `compactFrom` (half by default). The grabber is a button ' +
          'named for the sheet: drag it to a height, tap it to step up, or use Arrow Up and ' +
          'Arrow Down. Given an `onClose`, it also draws a close control beside the grabber — ' +
          'both 44px — and Escape inside the sheet dismisses it; without one it draws neither, ' +
          'because a surface with nowhere to close to should not offer the control. The body ' +
          'scrolls and is a tab stop, so a keyboard can reach prose that holds no controls of ' +
          'its own. The sheet is not modal — focus is never trapped and the map behind it ' +
          'stays reachable. It positions itself against its nearest positioned ancestor, at ' +
          '`--rt-z-place-sheet` so it clears the overlays it sits beside, and takes its ' +
          'heights from the `--rt-sheet-peek|half|full` ratios in `sheet.css`.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function Demo({
  initial,
  frame,
  dismissable = false,
}: {
  initial: SheetDetent;
  frame: CSSProperties;
  dismissable?: boolean;
}) {
  const [detent, setDetent] = useState<SheetDetent>(initial);
  const [closed, setClosed] = useState(false);
  if (closed) return <div style={frame} />;
  return (
    <div style={frame}>
      <Sheet
        detent={detent}
        onDetentChange={setDetent}
        label="Upper Pines"
        {...(dismissable ? { onClose: () => setClosed(true) } : null)}
      >
        <SheetHeader
          full={<h2 style={{ margin: 0 }}>Upper Pines</h2>}
          compact={<strong>Upper Pines</strong>}
        />
        <SheetSection from="peek">
          <p style={{ margin: 0 }}>The answer, stated once.</p>
        </SheetSection>
        <SheetSection from="half">
          <p style={{ margin: 0 }}>The detail that only matters once you lean in.</p>
        </SheetSection>
        <SheetSection from="full">
          <p style={{ margin: 0 }}>Everything else, for someone who wants all of it.</p>
        </SheetSection>
      </Sheet>
    </div>
  );
}

export const Peek: Story = { render: () => <Demo initial="peek" frame={PHONE_FRAME} /> };
export const Half: Story = { render: () => <Demo initial="half" frame={PHONE_FRAME} /> };
export const Full: Story = { render: () => <Demo initial="full" frame={PHONE_FRAME} /> };
export const Desktop: Story = { render: () => <Demo initial="half" frame={DESKTOP_FRAME} /> };
export const Dismissable: Story = {
  render: () => <Demo initial="half" frame={PHONE_FRAME} dismissable />,
};
