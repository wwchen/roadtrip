import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from '@ui';
import { isolatedDocs } from '@/test/story-api';
import { Drawer } from './Drawer';

const DOCS_FRAME_HEIGHT_PX = 640;
const MOBILE_VIEWPORT = 'mobile1';

function Content() {
  return (
    <div style={{ padding: '24px 20px' }}>
      <h2>Bowman Bay</h2>
      <p>Deception Pass State Park, Washington</p>
      <p>
        The drawer shell holds whatever page the selected pin opens. The map behind it keeps taking
        pan and zoom; the scrim is visual only.
      </p>
    </div>
  );
}

function Toggle() {
  const [open, setOpen] = useState(true);
  return (
    <>
      <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>
        Open the drawer
      </Button>
      <Drawer open={open} onClose={() => setOpen(false)}>
        <Content />
      </Drawer>
    </>
  );
}

const meta = {
  title: 'Drawer/Drawer',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The shell every pin opens into: a left-hand panel on desktop and a draggable bottom ' +
          'sheet on a phone. Close slides it out; the button reopens it.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Panel: Story = { render: () => <Toggle /> };

export const BottomSheet: Story = {
  render: () => <Toggle />,
  globals: { viewport: { value: MOBILE_VIEWPORT, isRotated: false } },
};
