import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { MeResponseDto } from '@/api/generated/api-types';
import { StoryApi, answer, isolatedDocs, storyPending, type StoryRoute } from '@/test/story-api';
import { AuthRow } from './AuthRow';

const DOCS_FRAME_HEIGHT_PX = 120;
const FRAME_WIDTH_PX = 360;
const FRAME_HEIGHT_PX = 80;
const ME_PATH = '/api/me';

const SIGNED_OUT: MeResponseDto = { authenticated: false, auth_enabled: true, auth_embedded: false, provider_label: 'Clerk' };

const SIGNED_IN: MeResponseDto = {
  ...SIGNED_OUT,
  authenticated: true,
  user: { id: 7, email: 'ada@example.test', display_name: 'Ada Lovelace', email_verified: true, roles: [] },
};

const EMAIL_ONLY: MeResponseDto = {
  ...SIGNED_IN,
  user: { id: 8, email: 'roan@example.test', email_verified: true, roles: [] },
};

function Corner({ me }: { me: StoryRoute }) {
  const [table] = useState(() => ({ [ME_PATH]: me }));
  return (
    <StoryApi routes={table}>
      <div style={{ position: 'relative', width: FRAME_WIDTH_PX, height: FRAME_HEIGHT_PX }}>
        <AuthRow />
      </div>
    </StoryApi>
  );
}

const meta = {
  title: 'Account/AuthRow',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The account pill in the map’s top-right corner: "Sign in" for a visitor, initials and ' +
          'first name once signed in. It renders nothing while /api/me is in flight or when no ' +
          'identity provider is configured.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const SignedOut: Story = { render: () => <Corner me={answer(SIGNED_OUT)} /> };

export const SignedIn: Story = { render: () => <Corner me={answer(SIGNED_IN)} /> };

export const SignedInWithoutName: Story = { render: () => <Corner me={answer(EMAIL_ONLY)} /> };

export const Resolving: Story = { render: () => <Corner me={storyPending} /> };

export const AuthDisabled: Story = {
  render: () => <Corner me={answer({ ...SIGNED_OUT, auth_enabled: false })} />,
};
