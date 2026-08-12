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
 * Every page fetches `/api/me` through this, so a saved preference shows up on
 * a new device without opening Settings.
 *
 * The effect depends on the derived theme string, not `dataUpdatedAt`: a
 * refetch reporting the same theme leaves it unchanged, so an unsaved preview
 * in `SettingsModal` survives.
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
