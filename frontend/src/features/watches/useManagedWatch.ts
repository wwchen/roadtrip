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

const HTTP_UNAUTHORIZED = 401;
const HTTP_NOT_FOUND = 404;

export interface ManagedWatchResult {
  watch: Watch | null;
  isPending: boolean;
  isLinkDead: boolean;
  /** Any other failure, which is worth a retry. */
  error: unknown;
  refetch: () => void;
}

/**
 * Links do not expire, so both statuses mean the alert was stopped. One message
 * for both: telling them apart would leak whether a watch id exists.
 */
export function isLinkDead(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  return status === HTTP_UNAUTHORIZED || status === HTTP_NOT_FOUND;
}

/** The one watch a magic link points at. */
export function useManagedWatch(link: MagicLink): ManagedWatchResult {
  const query = useQuery({
    queryKey: queryKeys.watches.detail(link.watchId),
    queryFn: ({ signal }) => getWatch(link.watchId, { signal, magicLinkToken: link.token }),
  });

  return {
    watch: query.data?.watch ?? null,
    isPending: query.isPending,
    isLinkDead: isLinkDead(query.error),
    error: isLinkDead(query.error) ? null : query.error,
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
