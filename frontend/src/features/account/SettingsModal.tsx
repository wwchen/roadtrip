import { useState } from 'react';
import { Banner, Button, Modal, Skeleton } from '@ui';
import { signOut } from '@/api/auth-api';
import { settingsErrorMessage } from '@/lib/settings-errors';
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

/**
 * Rebuild of web/account/settings-modal.js.
 *
 * Three tabs over one settings document: Profile and Notifications each save their
 * own slice; Account only fires actions and so has no Save.
 *
 * **Anchored on `dataUpdatedAt`.** Each panel's edits live here, seeded from the
 * loaded settings, and both panels are keyed on the query's `dataUpdatedAt` so a
 * successful save remounts them against the server's answer. That matters for more
 * than tidiness: saving a Slack token produces a new `slack_token_hint`, and the
 * masked field has to show the new one rather than the value it was seeded with. The
 * legacy modal achieved this by re-reading settings and re-mounting the panel by
 * hand.
 *
 * The tab bar is buttons, not the anchor pattern the availability dashboard uses:
 * these tabs are modal-local state with no URL of their own, so there is nothing to
 * link to. (LDS's `Tabs` still cannot report which tab was clicked — see
 * `TabNav.tsx` — so the buttons are hand-rolled either way.)
 *
 * No dirty-guard on close, matching the original: it closed on backdrop click with
 * no confirmation, and inventing one here would be a behaviour change dressed as a
 * port.
 */
export function SettingsModal({ onClose }: SettingsModalProps) {
  const settingsQuery = useSettings();
  const settings = settingsQuery.data;
  const version = settingsQuery.dataUpdatedAt;

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

  return (
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
    </Modal>
  );
}
