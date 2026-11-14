import type { UserTheme } from '@roadtrip/domain';

export interface UserProfile {
  id: bigint;
  email: string;
  emailVerified: boolean;
  displayName: string | null;
  theme: UserTheme;
}

export interface ActiveUser extends UserProfile {
  status: 'active';
}
