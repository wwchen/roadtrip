// Who the caller is.
//
// Replaces the `roadtrip:auth-changed` CustomEvent bus (web/availability/
// auth-events.js). That event existed to avoid an import cycle between the auth
// row and the watch consumers that must re-fetch when identity changes; a store
// both sides subscribe to removes the cycle without a global event.
//
// Fetching is NOT this store's job — `useMe()` (src/queries/auth.ts) owns the
// request through TanStack Query and writes the result here. The store exists so
// non-React code (the imperative map module, the window.__rt* shim) can read
// identity synchronously, and so `status` distinguishes "not asked yet" from
// "asked, and the caller is anonymous".
import { create } from 'zustand';
import type { Me, MeUser } from '@/api/auth-api';

export type AuthStatus =
  /** No answer from /api/me yet. Gate sign-in affordances on this. */
  | 'unknown'
  /** /api/me answered. `me` is populated; the caller may still be anonymous. */
  | 'ready';

export interface AuthState {
  status: AuthStatus;
  me: Me | null;
  /** Record an /api/me answer. */
  setMe: (me: Me) => void;
  /** Forget the identity and go back to `unknown` — e.g. after signing out. */
  reset: () => void;
}

const INITIAL: Pick<AuthState, 'status' | 'me'> = { status: 'unknown', me: null };

export const useAuthStore = create<AuthState>()((set) => ({
  ...INITIAL,
  setMe: (me) => set({ status: 'ready', me }),
  reset: () => set({ ...INITIAL }),
}));

// ---------------------------------------------------------------------------
// Selectors. Exported as plain functions so both `useAuthStore(selectUser)` in a
// component and `selectUser(useAuthStore.getState())` outside React work.
// ---------------------------------------------------------------------------

export const selectIsAuthenticated = (s: AuthState): boolean => s.me?.authenticated === true;

export const selectUser = (s: AuthState): MeUser | null => s.me?.user ?? null;

/**
 * False when no identity provider is configured — hide sign-in entirely rather
 * than offer a control that cannot work. Unknown until /api/me answers, so this
 * reports false while `status` is `unknown`.
 */
export const selectIsAuthEnabled = (s: AuthState): boolean => s.me?.auth_enabled === true;

/** True → mount the embedded email/password card; false → redirect to /auth/login. */
export const selectIsEmbeddedLogin = (s: AuthState): boolean => s.me?.auth_embedded === true;

/** The role the backend grants admin-only routes to (see RoadtripAuthorization). */
const ADMIN_ROLE = 'admin';

export const selectIsAdmin = (s: AuthState): boolean =>
  s.me?.user?.roles?.includes(ADMIN_ROLE) === true;
