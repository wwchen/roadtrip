// Watch trigger vocabulary shared by the watches page and inline editor.
import type { Watch } from '@/api/watches-api';

export const TRIGGER_KIND_SLACK_NOTIFY = 'slack_notify';
export const TRIGGER_KIND_EMAIL_NOTIFY = 'email_notify';
/** Add-to-cart. Set by the availability week, not offered by the watches form. */
export const TRIGGER_KIND_ATC = 'atc';

/** The editable trigger state behind the selector. */
export interface TriggerState {
  slackNotify: boolean;
  slackChannel: string;
  emailNotify: boolean;
  addToCart: boolean;
  stopWhenTriggered: boolean;
}

/** The subset of a watch payload the trigger editor produces. */
export interface TriggerPayload {
  trigger_kinds: string[];
  trigger_config: Record<string, unknown>;
  stop_when_triggered: boolean;
}

/**
 * A watch's trigger config, tolerating both the snake_case wire form and the
 * camelCase form the availability week used internally.
 */
function triggerConfigOf(watch: Partial<Watch> | null | undefined): Record<string, unknown> {
  const config =
    watch?.trigger_config ?? (watch as { triggerConfig?: unknown } | null)?.triggerConfig;
  return (config ?? {}) as Record<string, unknown>;
}

const nestedString = (config: Record<string, unknown>, kind: string, field: string): string => {
  const block = config[kind];
  if (!block || typeof block !== 'object') return '';
  const value = (block as Record<string, unknown>)[field];
  return typeof value === 'string' ? value : '';
};

export function watchHasTrigger(watch: Partial<Watch> | null | undefined, kind: string): boolean {
  return Array.isArray(watch?.trigger_kinds) && watch.trigger_kinds.includes(kind);
}

/**
 * The Slack channel a watch posts to.
 *
 * Falls back to a top-level `channel` key: watches created before the config was
 * namespaced per trigger kind still carry that shape, and an editor that dropped
 * it would silently clear the channel on the next save.
 */
export function watchSlackChannel(watch: Partial<Watch> | null | undefined): string {
  const config = triggerConfigOf(watch);
  const nested = nestedString(config, TRIGGER_KIND_SLACK_NOTIFY, 'channel');
  if (nested.trim()) return nested;
  const legacy = config.channel;
  return typeof legacy === 'string' && legacy.trim() ? legacy : '';
}

export function watchStopWhenTriggered(
  watch: Partial<Watch> | null | undefined,
  fallback = true,
): boolean {
  if (!watch) return fallback;
  const value =
    watch.stop_when_triggered ??
    (watch as { stopWhenTriggered?: unknown } | null)?.stopWhenTriggered;
  return value == null ? fallback : Boolean(value);
}

/** Read a watch into editable trigger state. */
export function triggerStateOf(watch: Partial<Watch> | null | undefined): TriggerState {
  return {
    // Slack defaults on for a new watch: it is the trigger every user has
    // configured, and a watch with no trigger cannot notify anyone.
    slackNotify: watch ? watchHasTrigger(watch, TRIGGER_KIND_SLACK_NOTIFY) : true,
    slackChannel: watch ? watchSlackChannel(watch) : '',
    emailNotify: watch ? watchHasTrigger(watch, TRIGGER_KIND_EMAIL_NOTIFY) : false,
    addToCart: watch ? watchHasTrigger(watch, TRIGGER_KIND_ATC) : false,
    stopWhenTriggered: watchStopWhenTriggered(watch, true),
  };
}

/**
 * Build the trigger half of a create/update payload.
 *
 * A kind is listed even when its config field is blank — the kind is the user's
 * intent, and the backend resolves a missing channel against the account's
 * stored default. Only the config entry is omitted.
 */
export function buildTriggerPayload(state: TriggerState): TriggerPayload {
  const triggerKinds: string[] = [];
  if (state.slackNotify) triggerKinds.push(TRIGGER_KIND_SLACK_NOTIFY);
  if (state.emailNotify) triggerKinds.push(TRIGGER_KIND_EMAIL_NOTIFY);
  if (state.addToCart) triggerKinds.push(TRIGGER_KIND_ATC);

  const triggerConfig: Record<string, unknown> = {};
  const channel = String(state.slackChannel || '').trim();
  if (state.slackNotify && channel) {
    triggerConfig[TRIGGER_KIND_SLACK_NOTIFY] = { channel };
  }
  return {
    trigger_kinds: triggerKinds,
    trigger_config: triggerConfig,
    stop_when_triggered: state.stopWhenTriggered,
  };
}
