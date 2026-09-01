import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Banner, Button, Modal, Skeleton } from '@ui';
import { signOut } from '@/api/auth-api';
import { coerceChoice } from '@/lib/theme';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { useThemeStore } from '@/stores/themeStore';
import { AccountPanel } from './AccountPanel';
import { AppearancePanel } from './AppearancePanel';
import {
  BookingPanel,
  bookingValuesOf,
  buildBookingPayload,
  isBookingDirty,
  type BookingValues,
} from './BookingPanel';
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
  isDisplayNameDirty,
  isThemeDirty,
  profileValuesOf,
  type ProfileValues,
} from './ProfilePanel';
import {
  useDisconnectSlack,
  useRecgovLogin,
  useRecgovMfa,
  useRecgovStatus,
  useRecgovVerify,
  useRemoveRecgov,
  useSaveBooking,
  useSaveNotifications,
  useSaveProfile,
  useSettings,
  useSettingsTests,
} from './useSettings';

const TAB_PROFILE = 'profile';
const TAB_APPEARANCE = 'appearance';
const TAB_NOTIFICATIONS = 'notifications';
const TAB_BOOKING = 'booking';
const TAB_ACCOUNT = 'account';

// Order is the rail's reading order. Appearance sits next to Profile because it
// saves the same document; Account last because it only fires actions.
const TABS = [
  { id: TAB_PROFILE, label: 'Profile' },
  { id: TAB_APPEARANCE, label: 'Appearance' },
  { id: TAB_NOTIFICATIONS, label: 'Notifications' },
  { id: TAB_BOOKING, label: 'Booking' },
  { id: TAB_ACCOUNT, label: 'Account' },
] as const;

type TabId = (typeof TABS)[number]['id'];

const SAVED_MESSAGE = 'Settings saved.';

/**
 * Removal is reported with what it cost: the watches now left without
 * credentials, and whether the saved browser session actually went with them.
 *
 * The local delete succeeds even when the companion is unreachable, so the
 * message must not imply a full wipe that did not happen — the session material
 * is still on the companion host in that case.
 */
const removedMessage = (stranded: number, profileDestroyed: boolean): string => {
  const base = profileDestroyed
    ? 'Recreation.gov credentials and saved browser session removed.'
    : 'Recreation.gov credentials removed, but the saved browser session could not be ' +
      'erased because the booking service is unreachable. Remove again once it is back.';
  if (stranded === 0) return base;
  return (
    `${base} ${stranded} active add-to-cart ` +
    `${stranded === 1 ? 'watch' : 'watches'} will fail until you add them again.`
  );
};

type Notice = { status: 'success' | 'error'; message: string };

export interface SettingsModalProps {
  onClose: () => void;
}

/**
 * Four sections over a side rail. Profile and Appearance edit two slices of the
 * one profile document and each save it; Notifications saves its own slice;
 * Account only fires actions and so has no Save.
 *
 * The rail is buttons, not the anchor pattern the availability dashboard uses:
 * these sections are modal-local state with no URL of their own, so there is
 * nothing to link to. (LDS's `Tabs` still cannot report which tab was clicked —
 * see `TabNav.tsx` — so the buttons are hand-rolled either way, same reasoning as
 * the horizontal tab bar before it, just stacked vertically now.)
 *
 * dataUpdatedAt keys editable panels so a save remounts fields from the server's
 * answer, including a newly generated Slack-token hint.
 */
export function SettingsModal({ onClose }: SettingsModalProps) {
  const settingsQuery = useSettings();
  const settings = settingsQuery.data;
  const version = settingsQuery.dataUpdatedAt;

  // In a ref so the revert effect below arms once, on unmount, not per edit.
  const savedChoice = settings ? coerceChoice(settings.profile.theme) : null;
  const savedChoiceRef = useRef(savedChoice);
  savedChoiceRef.current = savedChoice;

  // An unsaved preview must not outlive the modal: on close, the applied theme
  // goes back to the saved one. Unconditional because setChoice is idempotent.
  useEffect(
    () => () => {
      const saved = savedChoiceRef.current;
      if (saved) useThemeStore.getState().setChoice(saved);
    },
    [],
  );

  const [activeTab, setActiveTab] = useState<TabId>(TAB_PROFILE);
  const [notice, setNotice] = useState<Notice | null>(null);

  const saveProfile = useSaveProfile();
  const saveNotifications = useSaveNotifications();
  const saveBooking = useSaveBooking();
  const disconnectSlack = useDisconnectSlack();
  const removeRecgov = useRemoveRecgov();
  const { testSlack, testEmail } = useSettingsTests();
  // Its own query: the only settings read that waits on the booking companion,
  // so the modal opens without it.
  const recgovStatus = useRecgovStatus();
  const recgovLogin = useRecgovLogin();
  const recgovMfa = useRecgovMfa();
  const recgovVerify = useRecgovVerify();

  // Seeded per loaded document: `version` changes on every successful fetch, so
  // these reset to the server's values rather than keeping stale edits.
  const [profileValues, setProfileValues] = useState<ProfileValues | null>(null);
  const [notificationValues, setNotificationValues] = useState<NotificationValues | null>(null);
  const [bookingValues, setBookingValues] = useState<BookingValues | null>(null);
  const [seededVersion, setSeededVersion] = useState(0);

  if (settings && seededVersion !== version) {
    setSeededVersion(version);
    setProfileValues(profileValuesOf(settings));
    setNotificationValues(notificationValuesOf(settings));
    setBookingValues(bookingValuesOf(settings));
  }

  const saving = saveProfile.isPending || saveNotifications.isPending || saveBooking.isPending;

  // Each section gates on its own slice: Profile and Appearance share the profile
  // payload, so without the split either one would light up the other's Save.
  const dirty =
    settings != null &&
    ((activeTab === TAB_PROFILE &&
      profileValues != null &&
      isDisplayNameDirty(settings, profileValues)) ||
      (activeTab === TAB_APPEARANCE &&
        profileValues != null &&
        isThemeDirty(settings, profileValues)) ||
      (activeTab === TAB_NOTIFICATIONS &&
        notificationValues != null &&
        isNotificationsDirty(settings, notificationValues)) ||
      (activeTab === TAB_BOOKING && bookingValues != null && isBookingDirty(settings, bookingValues)));

  const fail = (err: unknown) =>
    setNotice({
      status: 'error',
      message: settingsErrorMessage((err as { code?: string } | null)?.code),
    });

  const save = async () => {
    if (!settings || saving || !dirty) return;
    setNotice(null);
    try {
      if ((activeTab === TAB_PROFILE || activeTab === TAB_APPEARANCE) && profileValues) {
        await saveProfile.mutateAsync(buildProfilePayload(profileValues));
      } else if (activeTab === TAB_NOTIFICATIONS && notificationValues) {
        await saveNotifications.mutateAsync(buildNotificationsPayload(notificationValues));
      } else if (activeTab === TAB_BOOKING && bookingValues) {
        await saveBooking.mutateAsync(buildBookingPayload(bookingValues));
      } else {
        return;
      }
      setNotice({ status: 'success', message: SAVED_MESSAGE });
    } catch (err) {
      fail(err);
    }
  };

  const handleRemoveRecgov = async () => {
    try {
      const { stranded_atc_watches, profile_destroyed } = await removeRecgov.mutateAsync();
      setNotice({
        status: 'success',
        message: removedMessage(stranded_atc_watches, profile_destroyed),
      });
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
      size="xl"
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

        <div className="rt-settings-body">
          <nav className="rt-settings-rail" aria-label="Settings sections">
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

          <div className="rt-settings-panel">
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
                {activeTab === TAB_APPEARANCE && profileValues && (
                  <AppearancePanel
                    key={`appearance:${version}`}
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
                {activeTab === TAB_BOOKING && bookingValues && (
                  <BookingPanel
                    key={`booking:${version}`}
                    settings={settings}
                    values={bookingValues}
                    onChange={setBookingValues}
                    status={recgovStatus.data}
                    statusPending={recgovStatus.isPending}
                    onLogin={recgovLogin}
                    onSubmitMfa={recgovMfa}
                    onVerify={recgovVerify}
                    onRemoveRecgov={() => void handleRemoveRecgov()}
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
        </div>
      </div>
    </Modal>,
    document.body,
  );
}
