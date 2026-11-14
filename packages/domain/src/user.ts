export const USER_STATUSES = ['active', 'disabled'] as const;
export const ACTIVE_USER_STATUS = 'active' satisfies UserStatus;
export const USER_THEMES = ['light', 'dark', 'system'] as const;

export type UserStatus = (typeof USER_STATUSES)[number];
export type UserTheme = (typeof USER_THEMES)[number];
