// Server state for the settings modal.
//
// Settings queries and mutations.
import { useEffect } from 'react';
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  clearSlack,
  fetchRecgovStatus,
  fetchSettings,
  removeRecgov,
  sendEmailTest,
  sendSlackTest,
  startRecgovLogin,
  submitRecgovMfa,
  updateBooking,
  updateNotifications,
  updateProfile,
  verifyRecgovSession,
  type RecgovLoginResponse,
  type RecgovRemovedResponse,
  type RecgovStatus,
  type RecgovVerifyResponse,
  type SettingsResponse,
  type UpdateBookingFields,
  type UpdateNotificationsFields,
} from '@/api/account-api';
import { coerceChoice } from '@/lib/theme';
import { queryKeys } from '@/queries/keys';
import { useThemeStore } from '@/stores/themeStore';

/**
 * The settings document. The server is the authority on theme: whenever a load
 * or a save resolves it, its theme wins over any applied preview.
 */
export function useSettings(): UseQueryResult<SettingsResponse> {
  const query = useQuery({
    queryKey: queryKeys.settings(),
    queryFn: ({ signal }) => fetchSettings({ signal }),
  });

  // Runs on each successful load and after each save, which is also what
  // refreshes the localStorage mirror the boot script reads.
  const serverTheme = query.data?.profile.theme;
  useEffect(() => {
    if (serverTheme === undefined) return;
    useThemeStore.getState().setChoice(coerceChoice(serverTheme));
  }, [serverTheme]);

  return query;
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
 * Save the rec.gov username and, when one was typed, a new password.
 *
 * Resolves to the credential summary rather than the whole document, so it
 * invalidates instead of seeding — same reasoning as `useDisconnectSlack`. The
 * refetch is what hands the panel its new password hint.
 */
export function useSaveBooking() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: UpdateBookingFields) => updateBooking(input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.settings() }),
  });
}

/**
 * Remove the stored rec.gov credentials.
 *
 * The response says how many active `atc` watches the removal stranded, which
 * is what the confirmation reports back to the user.
 */
export function useRemoveRecgov() {
  const queryClient = useQueryClient();
  return useMutation<RecgovRemovedResponse, unknown, void>({
    mutationFn: () => removeRecgov(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.settings() }),
  });
}

/**
 * The live rec.gov session state.
 *
 * Its own query so the modal renders without it: this is the one settings read
 * that can sit behind an unreachable companion. Retries are off because the
 * server already degrades to `companion_unavailable` rather than failing —
 * a retry would only make the row slower to say so.
 */
export function useRecgovStatus(): UseQueryResult<RecgovStatus> {
  return useQuery({
    queryKey: queryKeys.recgovStatus(),
    queryFn: ({ signal }) => fetchRecgovStatus({ signal }),
    retry: false,
  });
}

/**
 * Refetches the session row after an action that could have changed it.
 *
 * Shared by the three session actions below, which are plain functions rather
 * than mutations: they cache nothing themselves, and the panel's state machine
 * owns their pending state.
 */
function useSessionRefresh(): () => void {
  const queryClient = useQueryClient();
  return () => void queryClient.invalidateQueries({ queryKey: queryKeys.recgovStatus() });
}

/** Begin a login with the SAVED credentials. */
export function useRecgovLogin(): () => Promise<RecgovLoginResponse> {
  const refresh = useSessionRefresh();
  return async () => {
    const result = await startRecgovLogin();
    // Refetched on every outcome, `mfa_required` included. A pending challenge
    // has not changed the *session*, but it does change the status row's
    // `mfa_pending` — and the panel reads that to find its way back to the code
    // step after a Cancel, or after a reload. Skipping it here left
    // `mfa_pending` false for the life of the challenge, so the `profile_busy`
    // recovery could never recognise its own challenge holding the lock.
    refresh();
    return result;
  };
}

/** Complete the challenge the login opened. */
export function useRecgovMfa(): (code: string) => Promise<RecgovLoginResponse> {
  const refresh = useSessionRefresh();
  return async (code: string) => {
    const result = await submitRecgovMfa(code);
    refresh();
    return result;
  };
}

/** Dry-run session check. Never places a cart hold. */
export function useRecgovVerify(): () => Promise<RecgovVerifyResponse> {
  const refresh = useSessionRefresh();
  return async () => {
    const result = await verifyRecgovSession();
    refresh();
    return result;
  };
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
