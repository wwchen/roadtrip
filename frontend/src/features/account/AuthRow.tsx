// Embedded Auth0 login is intentionally unsupported; the active Clerk flow uses
// hosted redirects and keeps auth0-js out of the bundle.
import { useState } from 'react';
import { signIn, signOut } from '@/api/auth-api';
import { SettingsModal } from './SettingsModal';
import { useMe } from '@/queries/auth';
import './auth-row.css';

export function AuthRow() {
  const me = useMe().data;
  const [settingsOpen, setSettingsOpen] = useState(false);

  // Nothing at all when no identity provider is configured: a fresh clone with no
  // tenant should look exactly as it did before auth existed, rather than showing a
  // control that cannot work. `useMe` resolves for anonymous visitors too, so an
  // absent `me` here means the request has not landed yet.
  if (!me || me.auth_enabled === false) return null;

  if (!me.authenticated) {
    return (
      <div className="tb-auth">
        {/* A full-page navigation, not a fetch: the provider's hosted flow is
            cross-site, and fetch cannot follow that redirect. */}
        <button type="button" className="tb-auth-btn" onClick={() => signIn()}>
          Sign in
        </button>
      </div>
    );
  }

  // `display_name` is absent for providers that do not return one — and for Apple
  // after the first authorization — but the address is always there.
  const label = me.user?.display_name || me.user?.email || 'Signed in';

  return (
    <div className="tb-auth">
      <button
        type="button"
        className="tb-auth-who tb-auth-btn"
        title={me.user?.email ?? ''}
        onClick={() => setSettingsOpen(true)}
      >
        {label}
      </button>
      <button type="button" className="tb-auth-btn" onClick={() => signOut()}>
        Sign out
      </button>
      {settingsOpen ? <SettingsModal onClose={() => setSettingsOpen(false)} /> : null}
    </div>
  );
}
