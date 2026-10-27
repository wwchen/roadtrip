import { useState, type ComponentProps } from 'react';
import { AlertRow } from '@/domain/place/AlertRow';
import { NightSites } from '@/domain/place/NightSites';
import { NightsStrip } from '@/domain/place/NightsStrip';
import { PlaceVerdict } from '@/domain/place/PlaceVerdict';
import { POI_BLOCK_GROUPS, POI_FOOTER_BLOCKS, RULE_BEFORE_GROUP, type PoiBlockId } from '@/domain/poi/blocks';
import { PoiPageShell, type PoiBlockSlots } from '@/domain/poi/PoiPageShell';
import { Sheet, SheetHeader, SheetSection, type SheetDetent } from '@ui';
import './place-sheet.css';

export type PlaceArrival = 'from-map' | 'cold';

const ARRIVAL_DETENT = {
  'from-map': 'half',
  cold: 'peek',
} satisfies Record<PlaceArrival, SheetDetent>;

const PLACE_SHEET_TESTID = 'place-sheet';

const ABOVE_FOLD_DETENT: SheetDetent = 'half';
const BELOW_FOLD_DETENT: SheetDetent = 'full';

const ABOVE_FOLD_BLOCKS: readonly PoiBlockId[] = POI_BLOCK_GROUPS.slice(0, RULE_BEFORE_GROUP).flat();
const BELOW_FOLD_BLOCKS: readonly PoiBlockId[] = [
  ...POI_BLOCK_GROUPS.slice(RULE_BEFORE_GROUP).flat(),
  ...POI_FOOTER_BLOCKS,
];

function slotsFor(blocks: PoiBlockSlots, ids: readonly PoiBlockId[]): PoiBlockSlots | null {
  const slots: PoiBlockSlots = {};
  let filled = false;
  for (const id of ids) {
    if (blocks[id] == null) continue;
    slots[id] = blocks[id];
    filled = true;
  }
  return filled ? slots : null;
}

export interface PlaceSheetProps {
  name: string;
  arrival: PlaceArrival;
  verdict: ComponentProps<typeof PlaceVerdict>;
  nights: ComponentProps<typeof NightsStrip>;
  alert?: ComponentProps<typeof AlertRow>;
  siteNames?: ReadonlyMap<number, string>;
  blocks: PoiBlockSlots;
}

export function PlaceSheet({ name, arrival, verdict, nights, alert, siteNames, blocks }: PlaceSheetProps) {
  const [detent, setDetent] = useState<SheetDetent>(ARRIVAL_DETENT[arrival]);

  const selected = nights.days.find((day) => day.date === nights.selected) ?? null;
  const aboveFold = slotsFor(blocks, ABOVE_FOLD_BLOCKS);
  const belowFold = slotsFor(blocks, BELOW_FOLD_BLOCKS);

  return (
    <Sheet
      className="rt-place-sheet"
      data-testid={PLACE_SHEET_TESTID}
      detent={detent}
      onDetentChange={setDetent}
      label={name}
    >
      <SheetHeader
        full={<PlaceVerdict {...verdict} />}
        compact={<strong className="rt-place-sheet__name">{name}</strong>}
      />
      <SheetSection from={ABOVE_FOLD_DETENT}>
        <div className="rt-place-sheet__stay">
          <NightsStrip {...nights} />
          <NightSites day={selected} names={siteNames} />
          {alert ? <AlertRow {...alert} /> : null}
        </div>
      </SheetSection>
      {aboveFold ? (
        <SheetSection from={ABOVE_FOLD_DETENT}>
          <PoiPageShell blocks={aboveFold} />
        </SheetSection>
      ) : null}
      {belowFold ? (
        <SheetSection from={BELOW_FOLD_DETENT}>
          <PoiPageShell blocks={belowFold} />
        </SheetSection>
      ) : null}
    </Sheet>
  );
}
