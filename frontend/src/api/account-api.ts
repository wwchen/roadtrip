// All mutations return the updated settings object so callers can refresh state
// without a second round-trip.
//
// Error responses carry `{ error: "<code>", detail }`. The thrown HttpError has
// `.code` set to that string so callers can map it to specific copy.
import { jsonDeleteOk, jsonGetOk, jsonPostOk, jsonPutOk, type RequestOptions } from './http';

const SETTINGS_URL = '/api/settings';
const PROFILE_URL = '/api/settings/profile';
const NOTIFICATIONS_URL = '/api/settings/notifications';
const SLACK_URL = '/api/settings/notifications/slack';
const SLACK_TEST_URL = '/api/settings/notifications/slack/test';
const EMAIL_TEST_URL = '/api/settings/notifications/email/test';
const RECGOV_URL = '/api/settings/recgov';
const RECGOV_STATUS_URL = '/api/settings/recgov/status';
const RECGOV_LOGIN_URL = '/api/settings/recgov/login';
const RECGOV_MFA_URL = '/api/settings/recgov/login/mfa';
const RECGOV_VERIFY_URL = '/api/settings/recgov/verify';

/** Mirrors ProfileDto. */
export interface Profile {
  display_name: string | null;
  login_email: string;
  is_email_verified: boolean;
  roles: string[];
  provider_label: string | null;
  /** One of ThemeChoice. Narrow with `coerceChoice` before use — an older
   *  server may omit it. */
  theme: string;
}

/**
 * Mirrors NotificationsDto.
 *
 * The Slack token is never returned. `slack_configured` says whether one is
 * stored and `slack_token_hint` is a redacted fragment for display — the
 * write-only SecretField pattern Phase 3 rebuilds.
 */
export interface Notifications {
  notification_email: string | null;
  slack_channel: string | null;
  slack_configured: boolean;
  slack_token_hint: string | null;
}

/**
 * Mirrors BookingSettingsDto.
 *
 * Stored credentials only — a pure database read, which is why it rides in the
 * settings document. The live session state comes from `fetchRecgovStatus`,
 * whose own request is the one that can wait on the companion.
 *
 * The password is never returned: `recgov_configured` says whether one is
 * stored. There is deliberately no password hint: unlike a Slack bot token, a
 * human-chosen password's last characters are credential material, so the field
 * renders a fixed-length mask instead.
 */
export interface BookingSettings {
  recgov_configured: boolean;
  recgov_username: string | null;
}

/** Mirrors SettingsResponseDto — returned by the GET and by every mutation. */
export interface SettingsResponse {
  profile: Profile;
  notifications: Notifications;
  booking: BookingSettings;
}

/** Mirrors RecgovStatusDto. `session` is one of RecgovSessionState. */
export interface RecgovStatus {
  configured: boolean;
  username: string | null;
  session:
    | 'not_configured'
    | 'active'
    /** Credentials saved, this profile never signed in. Not a failure. */
    | 'not_logged_in'
    | 'expired'
    /** The booking service's own health check threw. Not the user's problem. */
    | 'check_failed'
    | 'companion_unavailable';
  detail?: string | null;
  /**
   * True while a login of this user's is waiting on a verification code. The
   * companion holds the prompt page for minutes, so a panel that remounted can
   * resume the step rather than orphan a challenge holding the profile's lock.
   */
  mfa_pending?: boolean;
}

/** Mirrors RecgovLoginResponseDto. A blocked login is a 200 with a code. */
export interface RecgovLoginResponse {
  status: 'ok' | 'mfa_required' | 'failed';
  challenge_id?: string | null;
  expires_at?: string | null;
  error?: string | null;
  detail?: string | null;
}

/** Mirrors RecgovVerifyResponseDto. The dry run never places a cart hold. */
export interface RecgovVerifyResponse {
  ok: boolean;
  error?: string | null;
  detail?: string | null;
}

/** Mirrors RecgovRemovedDto. */
export interface RecgovRemovedResponse {
  removed: boolean;
  stranded_atc_watches: number;
  companion_signed_out: boolean;
  /** False when the companion was unreachable: the saved browser session may remain on its host. */
  profile_destroyed: boolean;
}

export interface UpdateBookingFields {
  recgov_username: string;
  /** Only send this when the user typed a new password; null means "unchanged". */
  recgov_password?: string | null;
}

/** Mirrors SlackTestResponseDto. */
export interface SlackTestResponse {
  sent: boolean;
  channel?: string | null;
}

/** Mirrors EmailTestResponseDto. */
export interface EmailTestResponse {
  sent: boolean;
  recipient?: string | null;
}

export interface UpdateNotificationsFields {
  notification_email?: string;
  slack_channel?: string;
  /**
   * Only send this when the user has typed a new token. `null`/`undefined` mean
   * "unchanged" and the key is omitted from the request entirely — see
   * `updateNotifications`.
   */
  slack_token?: string | null;
}

export function fetchSettings({ signal }: RequestOptions = {}): Promise<SettingsResponse> {
  return jsonGetOk<SettingsResponse>(SETTINGS_URL, { signal });
}

export function updateProfile(
  { display_name, theme }: { display_name: string; theme: string },
  options: RequestOptions = {},
): Promise<SettingsResponse> {
  return jsonPutOk<SettingsResponse>(PROFILE_URL, { display_name, theme }, options);
}

/**
 * Update notification settings.
 *
 * `slack_token` is omitted from the request when its value is `null` or
 * `undefined` — the backend interprets a missing key as "unchanged", so callers
 * should only include it when the user has explicitly supplied a new token.
 * Sending `null` explicitly would not clear the token either; that is what
 * `clearSlack` is for.
 */
export function updateNotifications(
  { notification_email, slack_channel, slack_token }: UpdateNotificationsFields = {},
  options: RequestOptions = {},
): Promise<SettingsResponse> {
  const body: Record<string, string> = {};
  if (notification_email !== undefined) body.notification_email = notification_email;
  if (slack_channel !== undefined) body.slack_channel = slack_channel;
  // Omit slack_token entirely when null/undefined — "unchanged" per backend contract.
  if (slack_token != null) body.slack_token = slack_token;
  return jsonPutOk<SettingsResponse>(NOTIFICATIONS_URL, body, options);
}

/** Remove the stored Slack token and channel. Resolves to null (204). */
export function clearSlack(options: RequestOptions = {}): Promise<null> {
  return jsonDeleteOk<never>(SLACK_URL, options) as Promise<null>;
}

/**
 * Send a test message to the given Slack channel. `channel` is omitted when
 * null or undefined, in which case the server uses the stored channel.
 */
export function sendSlackTest(
  channel: string | null | undefined,
  options: RequestOptions = {},
): Promise<SlackTestResponse> {
  const body = channel != null ? { channel } : {};
  return jsonPostOk<SlackTestResponse>(SLACK_TEST_URL, body, options);
}

/** Send a test email to the user's notification email address. */
export function sendEmailTest(options: RequestOptions = {}): Promise<EmailTestResponse> {
  return jsonPostOk<EmailTestResponse>(EMAIL_TEST_URL, {}, options);
}

/**
 * Store the rec.gov username and, when the user typed one, a new password.
 *
 * `recgov_password` is omitted entirely when null — the backend reads a missing
 * key as "unchanged", the same contract `updateNotifications` follows for the
 * Slack token. Clearing is `removeRecgov`, never an empty save.
 */
export function updateBooking(
  { recgov_username, recgov_password }: UpdateBookingFields,
  options: RequestOptions = {},
): Promise<BookingSettings> {
  const body: Record<string, string> = { username: recgov_username };
  if (recgov_password != null) body.password = recgov_password;
  return jsonPutOk<BookingSettings>(RECGOV_URL, body, options);
}

/**
 * Remove the stored rec.gov credentials. Reports the active atc watches it strands.
 *
 * The count defaults to zero if the body is somehow absent: the caller shows it
 * in a confirmation, and "0" reads better there than a crash on a successful
 * delete.
 */
export async function removeRecgov(
  options: RequestOptions = {},
): Promise<RecgovRemovedResponse> {
  const body = await jsonDeleteOk<RecgovRemovedResponse>(RECGOV_URL, options);
  return body ?? { removed: true, stranded_atc_watches: 0, companion_signed_out: false, profile_destroyed: false };
}

/**
 * The stored credentials plus the live session state.
 *
 * Its own request, deliberately: it is the one settings read that talks to the
 * companion, and opening the modal must not wait on it.
 */
export function fetchRecgovStatus({ signal }: RequestOptions = {}): Promise<RecgovStatus> {
  return jsonGetOk<RecgovStatus>(RECGOV_STATUS_URL, { signal });
}

/** Begin a login with the SAVED credentials. May answer `mfa_required`. */
export function startRecgovLogin(options: RequestOptions = {}): Promise<RecgovLoginResponse> {
  return jsonPostOk<RecgovLoginResponse>(RECGOV_LOGIN_URL, {}, options);
}

/** Complete the challenge the login opened. The backend remembers which one. */
export function submitRecgovMfa(
  code: string,
  options: RequestOptions = {},
): Promise<RecgovLoginResponse> {
  return jsonPostOk<RecgovLoginResponse>(RECGOV_MFA_URL, { code }, options);
}

/** Dry-run session check. Never places a cart hold. */
export function verifyRecgovSession(
  options: RequestOptions = {},
): Promise<RecgovVerifyResponse> {
  return jsonPostOk<RecgovVerifyResponse>(RECGOV_VERIFY_URL, {}, options);
}
