// A visit authorized by a magic link. Reads one watch, carries a token, cannot
// list or create, and works for a visitor with no account.
import { useMutation, useQuery, useQueryClient, type UseMutationResult } from '@tanstack/react-query';
import {
  deleteWatch,
  getWatch,
  updateWatch,
  type Watch,
  type WatchResponse,
  type WatchStatus,
} from '@/api/watches-api';
import { queryKeys } from '@/queries/keys';
import type { MagicLink } from './magicLink';
import { describeError } from '@/lib/error-report';

const HTTP_UNAUTHORIZED = 401;
const HTTP_NOT_FOUND = 404;

export type LinkRefusal = 'token_refused' | 'not_on_account';

export interface ManagedWatchResult {
  watch: Watch | null;
  isPending: boolean;
  linkRefusal: LinkRefusal | null;
  rawError: string | null;
  /** Any other failure, which is worth a retry. */
  error: unknown;
  refetch: () => void;
}

export function linkRefusalOf(error: unknown): LinkRefusal | null {
  const status = (error as { status?: number } | null)?.status;
  if (status === HTTP_UNAUTHORIZED) return 'token_refused';
  if (status === HTTP_NOT_FOUND) return 'not_on_account';
  return null;
}

/** The one watch a magic link points at. */
export function useManagedWatch(link: MagicLink): ManagedWatchResult {
  const query = useQuery({
    queryKey: queryKeys.watches.detail(link.watchId),
    queryFn: ({ signal }) => getWatch(link.watchId, { signal, magicLinkToken: link.token }),
  });

  const linkRefusal = linkRefusalOf(query.error);
  return {
    watch: query.data?.watch ?? null,
    isPending: query.isPending,
    linkRefusal,
    error: linkRefusal ? null : query.error,
    rawError: query.error ? describeError(query.error) : null,
    refetch: () => void query.refetch(),
  };
}

/** Invalidates only the detail key — a link visitor has no lists loaded. */
export function useSetManagedWatchStatus(
  link: MagicLink,
): UseMutationResult<WatchResponse, unknown, WatchStatus> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (status: WatchStatus) =>
      updateWatch(link.watchId, { status }, { magicLinkToken: link.token }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.watches.detail(link.watchId) }),
  });
}

export function useStopManagedWatch(link: MagicLink): UseMutationResult<void, unknown, void> {
  // Deliberately no invalidate or remove on success. The key is still observed
  // by the card, so either would refetch a watch that no longer exists — a 404
  // that would replace the "stopped" banner with the dead-link screen. The card
  // renders the last known watch from cache instead.
  return useMutation({
    mutationFn: () => deleteWatch(link.watchId, { magicLinkToken: link.token }),
  });
}
