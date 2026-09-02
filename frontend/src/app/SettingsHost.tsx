// The settings modal's one mount point, on every page.
//
// It hangs off the store rather than off the account pill because the pill is on
// the map page alone, while the surfaces that send a user to Settings are not: the
// availability grid renders on the POI page too, and its "add your rec.gov login"
// hint would have been a dead end there.
import { SettingsModal } from '@/features/account/SettingsModal';
import { useSettingsStore } from '@/stores/settingsStore';

export function SettingsHost() {
  const open = useSettingsStore((state) => state.open);
  const tab = useSettingsStore((state) => state.tab);
  const closeSettings = useSettingsStore((state) => state.closeSettings);

  if (!open) return null;
  return <SettingsModal initialTab={tab ?? undefined} onClose={closeSettings} />;
}
