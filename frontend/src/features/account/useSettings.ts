// Server state for the settings modal.
//
// Replaces the imperative fetch/mutate/re-read chain in
// web/account/settings-modal.js.
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  clearSlack,
  fetchSettings,
  sendEmailTest,
  sendSlackTest,
  updateNotifications,
  updateProfile,
  type SettingsResponse,
  type UpdateNotificationsFields,
} from '@/api/account-api';
import { queryKeys } from '@/queries/keys';

export function useSettings(): UseQueryResult<SettingsResponse> {
  return useQuery({
    queryKey: queryKeys.settings(),
    queryFn: ({ signal }) => fetchSettings({ signal }),
  });
}

/**
 * Writes that replace the whole settings document.
 *
 * Every mutation here seeds the cache from its own response and then invalidates.
 * The legacy modal did the same thing by hand — save, then re-read, falling back to
 * the mutation's response if the re-read failed — because a save can change values
 * the client did not send: storing a Slack token produces a new `slack_token_hint`,
 * and the masked field has to show the new one.
 */
function useSettingsWrite<TInput>(mutationFn: (input: TInput) => Promise<SettingsResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: (updated) => {
      // Seed first so the panel reseeds from the server's answer even if the
      // refetch is slow or fails; then invalidate to reconcile.
      queryClient.setQueryData(queryKeys.settings(), updated);
      void queryClient.invalidateQueries({ queryKey: queryKeys.settings() });
    },
  });
}

export function useSaveProfile() {
  return useSettingsWrite((input: { display_name: string; theme: string }) => updateProfile(input));
}

export function useSaveNotifications() {
  return useSettingsWrite((input: UpdateNotificationsFields) => updateNotifications(input));
}

/**
 * Remove the stored Slack token and channel.
 *
 * Resolves to null rather than the settings document, so it invalidates instead of
 * seeding — the refetch is the only way to learn the new state.
 */
export function useDisconnectSlack() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => clearSlack(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.settings() }),
  });
}

/**
 * The two test-send actions.
 *
 * Plain functions rather than mutations: they change nothing on the server that
 * this client caches, and the panel owns their pending/status display.
 */
export function useSettingsTests() {
  return {
    testSlack: async (channel: string): Promise<void> => {
      await sendSlackTest(channel || undefined);
    },
    testEmail: async (): Promise<void> => {
      await sendEmailTest();
    },
  };
}
