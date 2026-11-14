import { userSettings, type Db } from '@roadtrip/db';
import { eq } from 'drizzle-orm';
import type { NotificationSettings } from '../service/settings/model';

export async function notificationsFor(db: Db, userId: bigint, loginEmail: string): Promise<NotificationSettings> {
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
    notificationEmail: row?.notificationEmail ?? loginEmail,
    slackChannel: row?.slackChannel ?? null,
    slackConfigured: row?.slackConfigured != null,
    slackTokenHint: row?.slackTokenHint ?? null,
  };
}
