import { useState } from 'react';
import { Button } from '@lew-ds/lds-react';
import { SeededTextField } from './SeededTextField';

export interface SecretFieldProps {
  label: string;
  /**
   * Redacted fragment of the stored secret (e.g. the last four characters), or null
   * when there is none to show. The secret itself is never sent to the client.
   *
   * **Machine tokens only.** A Slack bot token's last 4 characters say *which*
   * token is stored without helping anyone guess it. For a human-chosen
   * password they are credential material, so the rec.gov field stores and
   * shows none — it passes [stored] instead and gets a fixed-length mask.
   */
  hint?: string | null;
  /**
   * Whether a secret is stored, when that cannot be inferred from [hint].
   * Defaults to "a hint means something is stored", which is what every
   * hint-bearing caller relies on.
   */
  stored?: boolean;
  help?: string;
  id: string;
  name?: string;
  /** The pending secret: a string the user typed, or null for "leave unchanged". */
  value: string | null;
  onChange: (value: string | null) => void;
}

/** Prefix for a stored secret shown alongside its hint. */
const MASK = '••••';

/**
 * The whole mask for a secret with no hint.
 *
 * Fixed length on purpose: rendering one dot per stored character would leak
 * the password's length, which is the second thing a guesser wants after its
 * last few characters.
 */
const OPAQUE_MASK = '••••••••••';

/**
 * A write-only secret input.
 *
 * Replaces `web/design-system/secret-field.js`. The server never returns the secret
 * — only whether one is stored and a redacted `hint` — so this field can show a
 * masked placeholder and accept a replacement, but never display or round-trip the
 * real value.
 *
 * `value === null` means **leave unchanged**, and that is the contract the API
 * client depends on: `updateNotifications` omits `slack_token` entirely when it is
 * null, because the backend reads a missing key as "unchanged".
 *
 * **This port fixes two bugs the original had, both from one root cause:** the
 * legacy state machine used `mode: 'replacing'` for two different things — "the user
 * clicked Replace" and "there is nothing stored, so just show an input". With no
 * secret stored it therefore opened in `replacing` mode, which meant:
 *
 *  1. `getValue()` returned `''` rather than `null`, so saving an unrelated field
 *     sent `slack_token: ""` — a value the user never entered.
 *  2. `isDirty()` was true from the moment the panel opened, since dirty was
 *     `getMode() === 'replacing'`. Save was enabled with nothing edited, and a
 *     discard-changes guard would fire on a form nobody had touched.
 *
 * Here the two are separate: `replacing` is local presentation state, while the
 * reported value is `null` until something is actually typed. An empty input reads
 * as "no secret supplied", never as "store an empty secret" — clearing a stored
 * secret is a different, explicit action (`clearSlack`), not an empty save.
 */
export function SecretField({
  label,
  hint = null,
  stored,
  help,
  id,
  name,
  value,
  onChange,
}: SecretFieldProps) {
  const hasHint = hint != null && hint !== '';
  const hasStored = stored ?? hasHint;
  // With nothing stored there is nothing to reveal or keep, so the input is the
  // only sensible resting state and there is no Replace step to offer.
  const [replacing, setReplacing] = useState(!hasStored);

  if (hasStored && !replacing) {
    return (
      <div className="rt-secret-field">
        <span className="rt-secret-field-label">{label}</span>
        <span className="rt-secret-field-mask">
          {hasHint ? `${MASK}${hint}` : OPAQUE_MASK}
        </span>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => {
            setReplacing(true);
            // Still "unchanged" until something is typed.
            onChange(null);
          }}
        >
          Replace
        </Button>
        {help ? <span className="rt-secret-field-help">{help}</span> : null}
      </div>
    );
  }

  return (
    <div className="rt-secret-field">
      {/* Seeded, not controlled: the parent re-renders on every keystroke, and a
          changing `value` prop would swap this input's DOM and eat the caret. */}
      <SeededTextField
        id={id}
        name={name}
        label={label}
        type="password"
        help={help}
        autoComplete="off"
        seed={value ?? ''}
        onChange={(e) => {
          const typed = (e.target as HTMLInputElement).value;
          // Empty means "no secret supplied", not "store an empty secret".
          onChange(typed === '' ? null : typed);
        }}
      />
      {hasStored ? (
        <Button
          size="sm"
          variant="tertiary"
          onClick={() => {
            setReplacing(false);
            onChange(null);
          }}
        >
          Cancel
        </Button>
      ) : null}
    </div>
  );
}
