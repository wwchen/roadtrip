import { userSettings, type Db } from '@roadtrip/db';
import { eq } from 'drizzle-orm';
import type { NotificationsDto } from '../contract';

export async function notificationsFor(db: Db, userId: bigint, loginEmail: string): Promise<NotificationsDto> {
  const [row] = await db
    .select({
      notificationEmail: userSettings.notificationEmail,
      slackChannel: userSettings.slackChannel,
      slackConfigured: userSettings.slackTokenCipher,
      slackTokenHint: userSettings.slackTokenHint,
    })
    .from(userSettings)
    .where(eq(userSettings.userId, userId));
  return {
    notification_email: row?.notificationEmail ?? loginEmail,
    ...(row?.slackChannel == null ? {} : { slack_channel: row.slackChannel }),
    slack_configured: row?.slackConfigured != null,
    ...(row?.slackTokenHint == null ? {} : { slack_token_hint: row.slackTokenHint }),
  };
}
