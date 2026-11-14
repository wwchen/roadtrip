import { USER_THEMES, type UserTheme } from '@roadtrip/domain';
import type { BookingSettingsDto, NotificationsDto, SettingsResponseDto } from '../../contract';
import type { BookingSettings, NotificationSettings, ProfilePatch, SettingsView } from '../../service/settings/model';
import { errorAnswer, INVALID_BODY_DETAIL, INVALID_FIELD, invalidBody, isTextOrAbsent, okAnswer, STATUS_BAD_REQUEST, type HttpAnswer, type Parsed } from './httpAnswer';

const BLANK_DISPLAY_NAME_DETAIL = 'display_name must not be blank';
const THEME_DETAIL = `theme must be one of ${USER_THEMES.join(', ')}`;

const invalidField = (detail: string): Parsed<never> => ({ kind: 'invalid', answer: errorAnswer(STATUS_BAD_REQUEST, INVALID_FIELD, detail) });

export function parseProfilePatch(body: unknown): Parsed<ProfilePatch> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return invalidBody(INVALID_BODY_DETAIL);
  const { display_name: displayName, theme } = body as Record<string, unknown>;
  if (!isTextOrAbsent(displayName) || !isTextOrAbsent(theme)) return invalidBody(INVALID_BODY_DETAIL);
  if (typeof displayName === 'string' && displayName.trim().length === 0) return invalidField(BLANK_DISPLAY_NAME_DETAIL);
  if (typeof theme === 'string' && !(USER_THEMES as readonly string[]).includes(theme)) return invalidField(THEME_DETAIL);
  return {
    kind: 'ok',
    value: {
      ...(typeof displayName === 'string' ? { displayName } : {}),
      ...(typeof theme === 'string' ? { theme: theme as UserTheme } : {}),
    },
  };
}

export function notificationsDto(row: NotificationSettings): NotificationsDto {
  return {
    notification_email: row.notificationEmail,
    ...(row.slackChannel === null ? {} : { slack_channel: row.slackChannel }),
    slack_configured: row.slackConfigured,
    ...(row.slackTokenHint === null ? {} : { slack_token_hint: row.slackTokenHint }),
  };
}

export function bookingBody(settings: BookingSettings): BookingSettingsDto {
  return settings.configured ? { recgov_configured: true, recgov_username: settings.username } : { recgov_configured: false };
}

export function settingsAnswer(view: SettingsView): HttpAnswer<SettingsResponseDto> {
  const { profile } = view;
  return okAnswer({
    profile: {
      ...(profile.displayName === null ? {} : { display_name: profile.displayName }),
      login_email: profile.email,
      is_email_verified: profile.emailVerified,
      roles: profile.roles,
      theme: profile.theme,
    },
    notifications: notificationsDto(view.notifications),
    booking: bookingBody(view.booking),
  });
}
