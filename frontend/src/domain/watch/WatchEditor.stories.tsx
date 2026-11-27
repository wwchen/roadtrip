import { useEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { WatchEditor } from './WatchEditor';
import { normalizeWatchCapabilities } from '@/lib/watch-windows';

const ZION_THEME_CLASS = 'theme-roadtrip-zion';

const PADDING = 24;

function Themed({ children }: { children: ReactNode }) {
  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
  }, []);
  return <>{children}</>;
}

const meta = {
  title: 'Domain/WatchEditor',
  parameters: {
    docs: {
      description: {
        component:
          'What the watch editor shows when add-to-cart is present but this caller ' +
          'cannot use it. The control keeps its shape and the help line becomes the ' +
          'one step that unlocks it: that booking site’s credentials in Settings. ' +
          'The line names whichever vendor the backend served for that pin, so the ' +
          'state has a named and a neutral story below.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function editorWithoutCredentials(providerDisplay?: string) {
  return (
    <Themed>
      <div style={{ padding: PADDING }}>
        <WatchEditor
          title="Watch Bowman Bay"
          subtitle="Tuesday, August 11"
          watch={null}
          capabilities={normalizeWatchCapabilities({
            trigger_kinds: ['slack_notify', 'email_notify'],
            add_to_cart: { state: 'no_credentials', provider_display: providerDisplay },
          })}
          onSave={async () => {}}
          onSignIn={() => {}}
          onOpenSettings={() => {}}
          onClose={() => {}}
        />
      </div>
    </Themed>
  );
}

export const EditorWithoutCredentials: Story = {
  parameters: {
    docs: {
      description: {
        story:
          'The editor a signed-in user without credentials gets: add-to-cart stays ' +
          'visible and disabled, and its help line is the way to fix that.',
      },
    },
  },
  render: () => editorWithoutCredentials(),
};

export const EditorWithoutCredentialsNamed: Story = {
  parameters: {
    docs: {
      description: {
        story: 'The same editor once the backend names the adapter that would hold the site.',
      },
    },
  },
  render: () => editorWithoutCredentials('Campflare'),
};
