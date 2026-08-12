import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Banner, Button, Modal, Skeleton } from '@ui';
import { signOut } from '@/api/auth-api';
import { coerceChoice } from '@/lib/theme';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { useThemeStore } from '@/stores/themeStore';
import { AccountPanel } from './AccountPanel';
import {
  NotificationsPanel,
  buildNotificationsPayload,
  isNotificationsDirty,
  notificationValuesOf,
  type NotificationValues,
} from './NotificationsPanel';
import {
  ProfilePanel,
  buildProfilePayload,
  isProfileDirty,
  profileValuesOf,
  type ProfileValues,
} from './ProfilePanel';
import {
  useDisconnectSlack,
  useSaveNotifications,
  useSaveProfile,
  useSettings,
  useSettingsTests,
} from './useSettings';

const TAB_PROFILE = 'profile';
const TAB_NOTIFICATIONS = 'notifications';
const TAB_ACCOUNT = 'account';

const TABS = [
  { id: TAB_PROFILE, label: 'Profile' },
  { id: TAB_NOTIFICATIONS, label: 'Notifications' },
  { id: TAB_ACCOUNT, label: 'Account' },
] as const;

type TabId = (typeof TABS)[number]['id'];

const SAVED_MESSAGE = 'Settings saved.';

type Notice = { status: 'success' | 'error'; message: string };

export interface SettingsModalProps {
  onClose: () => void;
}

// dataUpdatedAt keys editable panels so a save remounts fields from the server's
// answer, including a newly generated Slack-token hint.
export function SettingsModal({ onClose }: SettingsModalProps) {
  const settingsQuery = useSettings();
  const settings = settingsQuery.data;
  const version = settingsQuery.dataUpdatedAt;

  // The saved choice, tracked in a ref so the revert-on-close effect below runs
  // its cleanup exactly once, on unmount, rather than on every edit.
  const savedChoice = settings ? coerceChoice(settings.profile.theme) : null;
  const savedChoiceRef = useRef(savedChoice);
  savedChoiceRef.current = savedChoice;

  // Previewing a theme (ProfilePanel's Appearance control) applies it to the
  // document immediately, ahead of Save. If the modal closes without saving,
  // that preview must not outlive it — the applied theme has to equal the saved
  // theme whenever the modal is closed. `[]` deps: this arms the cleanup once,
  // on unmount, reading the latest saved choice through the ref rather than
  // re-subscribing on every keystroke.
  useEffect(
    () => () => {
      const saved = savedChoiceRef.current;
      // Unconditional: setChoice is idempotent, so a saved preview costs one
      // no-op rather than an equality check that could go stale.
      if (saved) useThemeStore.getState().setChoice(saved);
    },
    [],
  );

  const [activeTab, setActiveTab] = useState<TabId>(TAB_PROFILE);
  const [notice, setNotice] = useState<Notice | null>(null);

  const saveProfile = useSaveProfile();
  const saveNotifications = useSaveNotifications();
  const disconnectSlack = useDisconnectSlack();
  const { testSlack, testEmail } = useSettingsTests();

  // Seeded per loaded document: `version` changes on every successful fetch, so
  // these reset to the server's values rather than keeping stale edits.
  const [profileValues, setProfileValues] = useState<ProfileValues | null>(null);
  const [notificationValues, setNotificationValues] = useState<NotificationValues | null>(null);
  const [seededVersion, setSeededVersion] = useState(0);

  if (settings && seededVersion !== version) {
    setSeededVersion(version);
    setProfileValues(profileValuesOf(settings));
    setNotificationValues(notificationValuesOf(settings));
  }

  const saving = saveProfile.isPending || saveNotifications.isPending;

  const dirty =
    settings != null &&
    ((activeTab === TAB_PROFILE && profileValues != null && isProfileDirty(settings, profileValues)) ||
      (activeTab === TAB_NOTIFICATIONS &&
        notificationValues != null &&
        isNotificationsDirty(settings, notificationValues)));

  const fail = (err: unknown) =>
    setNotice({
      status: 'error',
      message: settingsErrorMessage((err as { code?: string } | null)?.code),
    });

  const save = async () => {
    if (!settings || saving || !dirty) return;
    setNotice(null);
    try {
      if (activeTab === TAB_PROFILE && profileValues) {
        await saveProfile.mutateAsync(buildProfilePayload(profileValues));
      } else if (activeTab === TAB_NOTIFICATIONS && notificationValues) {
        await saveNotifications.mutateAsync(buildNotificationsPayload(notificationValues));
      } else {
        return;
      }
      setNotice({ status: 'success', message: SAVED_MESSAGE });
    } catch (err) {
      fail(err);
    }
  };

  const handleDisconnectSlack = async () => {
    try {
      await disconnectSlack.mutateAsync();
      setNotice(null);
    } catch (err) {
      fail(err);
    }
  };

  // Through a portal to `document.body`, and only here: LDS's `.lds-modal-scrim` has
  // a background and centres its child but sets NO position, so rendered in place it
  // is an in-flow block. Mounted from the topbar — its only trigger — that put the
  // whole settings form inside a 420px panel with no overlay and nothing blocked.
  // The positioning lives in `account.css` beside this component; upstream is where
  // it belongs, but the published package does not have it.
  return createPortal(
    <Modal
      title="Settings"
      onClose={onClose}
      actions={
        // Account has nothing to save, so it gets no button rather than a
        // permanently disabled one.
        activeTab === TAB_ACCOUNT ? undefined : (
          <Button variant="primary" disabled={!dirty || saving} onClick={() => void save()}>
            Save
          </Button>
        )
      }
    >
      <div className="rt-settings-modal">
        {notice && (
          <Banner status={notice.status} dismissible onDismiss={() => setNotice(null)} role="status">
            {notice.message}
          </Banner>
        )}

        <nav className="rt-settings-tabs" aria-label="Settings sections">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              aria-current={tab.id === activeTab ? 'true' : undefined}
              onClick={() => {
                setActiveTab(tab.id);
                setNotice(null);
              }}
            >
              {tab.label}
            </button>
          ))}
        </nav>

        {settingsQuery.isPending ? (
          <Skeleton aria-label="Loading settings" />
        ) : settingsQuery.isError || !settings ? (
          <Banner status="error">
            {settingsErrorMessage((settingsQuery.error as { code?: string } | null)?.code)}
          </Banner>
        ) : (
          <>
            {activeTab === TAB_PROFILE && profileValues && (
              <ProfilePanel
                key={`profile:${version}`}
                profile={settings.profile}
                values={profileValues}
                onChange={setProfileValues}
              />
            )}
            {activeTab === TAB_NOTIFICATIONS && notificationValues && (
              <NotificationsPanel
                key={`notifications:${version}`}
                settings={settings}
                values={notificationValues}
                onChange={setNotificationValues}
                onTestSlack={testSlack}
                onTestEmail={testEmail}
              />
            )}
            {activeTab === TAB_ACCOUNT && (
              <AccountPanel
                settings={settings}
                onSignOut={signOut}
                onDisconnectSlack={() => void handleDisconnectSlack()}
              />
            )}
          </>
        )}
      </div>
    </Modal>,
    document.body,
  );
}
