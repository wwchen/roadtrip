import type { ReactNode } from 'react';
import { Icon } from '@ui';
import './account.css';

/** How a status line reads at a glance. Each tone owns its glyph and colour. */
export type StatusTone = 'ok' | 'error' | 'warn' | 'muted';

const GLYPH: Record<StatusTone, string> = {
  ok: 'check',
  error: 'close',
  warn: 'warning',
  muted: 'clock',
};

/**
 * One inline status line: a glyph and a short message, announced as a status.
 *
 * Extracted from `NotificationsPanel`'s test result at the second site — the
 * booking panel's session row and its login/verify result — rather than written
 * again. The glyph is this component's to draw, from `tone`: a message carrying
 * its own tick cannot be read out or restyled as one.
 */
export function AccountStatusText({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return (
    <span className={`rt-notif-status rt-notif-status--${tone}`} role="status">
      <Icon name={GLYPH[tone]} aria-hidden="true" /> {children}
    </span>
  );
}
