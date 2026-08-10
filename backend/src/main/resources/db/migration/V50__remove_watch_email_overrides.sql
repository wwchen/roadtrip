-- Email alert recipients now resolve dynamically from the watch owner's
-- notification settings (falling back to their login email). Watch-level
-- recipient overrides are no longer part of the trigger-config contract.
UPDATE availability_watch
SET trigger_config = trigger_config - 'email_notify'
WHERE trigger_config ? 'email_notify';
