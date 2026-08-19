// Embedded Auth0 login is intentionally unsupported; the active Clerk flow uses
// hosted redirects and keeps auth0-js out of the bundle.
import { useState } from 'react';
import { signIn } from '@/api/auth-api';
import { SettingsModal } from './SettingsModal';
import { useAcctClearance } from './useAcctClearance';
import { useMe } from '@/queries/auth';
import './auth-row.css';

/** "Roan Carter" -> "RC"; a single name falls back to its first letter. */
function initialsOf(name: string): string {
  const [first, last] = name.trim().split(/\s+/);
  return last ? `${first[0]}${last[0]}`.toUpperCase() : first[0]?.toUpperCase() ?? '';
}

/**
 * The account control: a floating top-right pill, matching the layers panel's
 * corner treatment. Signed out it is a "Sign in" button; signed in it is an
 * avatar-and-name button that opens `SettingsModal`, where sign-out lives
 * (behind a confirm, on the Account tab) so this control does not need its own.
 */
export function AuthRow() {
  const me = useMe().data;
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pillNode, setPillNode] = useState<HTMLButtonElement | null>(null);
  useAcctClearance(pillNode);

  // Nothing at all when no identity provider is configured: a fresh clone with no
  // tenant should look exactly as it did before auth existed, rather than showing a
  // control that cannot work. `useMe` resolves for anonymous visitors too, so an
  // absent `me` here means the request has not landed yet.
  if (!me || me.auth_enabled === false) return null;

  if (!me.authenticated) {
    return (
      // A full-page navigation, not a fetch: the provider's hosted flow is
      // cross-site, and fetch cannot follow that redirect.
      <button
        ref={setPillNode}
        type="button"
        className="acct-pill acct-pill--signin"
        onClick={() => signIn()}
      >
        Sign in
      </button>
    );
  }

  // `display_name` is absent for providers that do not return one — and for Apple
  // after the first authorization — but the address is always there.
  const label = me.user?.display_name || me.user?.email || 'Signed in';
  const firstName = label.split(/\s+/)[0];

  return (
    <>
      <button
        ref={setPillNode}
        type="button"
        className="acct-pill"
        title={me.user?.email ?? ''}
        onClick={() => setSettingsOpen(true)}
      >
        <span className="acct-avatar" aria-hidden="true">
          {initialsOf(label)}
        </span>
        <span className="acct-name">{firstName}</span>
      </button>
      {settingsOpen ? <SettingsModal onClose={() => setSettingsOpen(false)} /> : null}
    </>
  );
}
