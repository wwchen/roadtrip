import type { CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { AlertRow } from './AlertRow';

const PHONE_FRAME: CSSProperties = { width: 390, padding: 16, boxSizing: 'border-box' };
const DESKTOP_FRAME: CSSProperties = { width: 720, padding: 24, boxSizing: 'border-box' };
const NIGHT = '2026-10-27';
const BASE = { night: NIGHT, onCreate: () => undefined };

const meta = {
  title: 'Place/AlertRow',
  component: AlertRow,
  decorators: [
    (Story, { parameters }) => (
      <div style={parameters.desktop ? DESKTOP_FRAME : PHONE_FRAME}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'The only control in the product that offers to create a watch: a night the caller was ' +
          'already looking at, shut, with one tap to be told when it opens. The caller decides ' +
          'from the wire\'s `watchable` whether to render it at all; `gate` is the separate ' +
          'question of whether this caller can use it. A gated control keeps its shape and its ' +
          'action becomes the one step that unlocks it, so `signed-out` still renders a button. ' +
          'Only `blocked` is inert, because there is nothing the visitor could do.',
      },
    },
  },
} satisfies Meta<typeof AlertRow>;

export default meta;
type Story = StoryObj<typeof AlertRow>;

export const Ready: Story = { args: { ...BASE, gate: 'ready' } };
export const Pending: Story = { args: { ...BASE, gate: 'ready', pending: true } };
export const SignedOut: Story = { args: { ...BASE, gate: 'signed-out', onSignIn: () => undefined } };
export const Blocked: Story = { args: { ...BASE, gate: 'blocked' } };
export const Desktop: Story = { args: { ...BASE, gate: 'ready' }, parameters: { desktop: true } };
