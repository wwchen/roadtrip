import { useEffect } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchMe, type Me } from '@/api/auth-api';
import { coerceChoice } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';
import { queryKeys } from './keys';

/**
 * Identity is checked on every page load and gates the whole UI, so it is worth
 * a little more freshness than the default.
 */
const ME_STALE_TIME_MS = 10_000;

/**
 * Identity, plus the side effect of applying a signed-in user's saved theme.
 *
 * `/api/me` is fetched by every page through this hook (mounted via
 * `AuthRow`), so this is what makes a saved preference visible on a new
 * device, in incognito, or after clearing storage — without waiting for the
 * user to open Settings, which `useSettings` (`features/account/useSettings.ts`)
 * only fetches once the modal is mounted.
 *
 * Both hooks push a server-reported theme into `themeStore`; that overlap is
 * deliberate rather than accidental, see `useSettings`'s docstring and the
 * design doc for the reasoning. What keeps this one from clobbering a live,
 * unsaved preview in `SettingsModal`: the effect depends on the *derived*
 * theme string, not on `dataUpdatedAt` or an `onSuccess` callback. A routine
 * refetch (window focus, staleness) that reports the same theme as before
 * leaves this dependency unchanged, so the effect does not re-run and the
 * preview survives. Only a theme that has genuinely changed server-side
 * re-fires it — matching `useSettings`'s own "the document's theme wins"
 * rule for its own fetches.
 */
export function useMe(): UseQueryResult<Me> {
  const query = useQuery({
    queryKey: queryKeys.me(),
    queryFn: ({ signal }) => fetchMe({ signal }),
    staleTime: ME_STALE_TIME_MS,
  });

  // Anonymous callers (no `user`, or a user with no theme reported) leave the
  // store untouched — they follow `prefers-color-scheme`, and the server has
  // nothing to say about them.
  const meTheme = query.data?.user?.theme;
  useEffect(() => {
    if (meTheme == null) return;
    useThemeStore.getState().setChoice(coerceChoice(meTheme));
  }, [meTheme]);

  return query;
}
