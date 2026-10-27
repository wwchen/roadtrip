import { useState, type ComponentProps, type ReactNode } from 'react';
import { AlertRow } from '@/domain/place/AlertRow';
import { NightSites } from '@/domain/place/NightSites';
import { NightsStrip } from '@/domain/place/NightsStrip';
import { PlaceVerdict } from '@/domain/place/PlaceVerdict';
import { POI_BLOCK_GROUPS, POI_FOOTER_BLOCKS, type PoiBlockId } from '@/domain/poi/blocks';
import { PoiPageShell, type PoiBlockSlots } from '@/domain/poi/PoiPageShell';
import { Sheet, SheetHeader, SheetSection, type SheetDetent } from '@ui';
import './place-sheet.css';

export type PlaceArrival = 'from-map' | 'cold';

const ARRIVAL_DETENT = {
  'from-map': 'half',
  cold: 'peek',
} satisfies Record<PlaceArrival, SheetDetent>;

const PLACE_SHEET_TESTID = 'place-sheet';

const NAME_HEADING = 'h2';

const PEEK_DETENT: SheetDetent = 'peek';
const HALF_DETENT: SheetDetent = 'half';
const FULL_DETENT: SheetDetent = 'full';

const PEEK_GROUP_COUNT = 1;
const HALF_GROUP_COUNT = 3;
const SHEET_OMITS: readonly PoiBlockId[] = ['hero', 'identity'];

const sheetBlocks = (ids: readonly PoiBlockId[]): readonly PoiBlockId[] =>
  ids.filter((id) => !SHEET_OMITS.includes(id));

const PEEK_BLOCKS = sheetBlocks(POI_BLOCK_GROUPS.slice(0, PEEK_GROUP_COUNT).flat());
const HALF_BLOCKS = sheetBlocks(POI_BLOCK_GROUPS.slice(PEEK_GROUP_COUNT, HALF_GROUP_COUNT).flat());
const FULL_BLOCKS = sheetBlocks([
  ...POI_BLOCK_GROUPS.slice(HALF_GROUP_COUNT).flat(),
  ...POI_FOOTER_BLOCKS,
]);

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
  subtitle?: string;
  arrival: PlaceArrival;
  verdict?: ComponentProps<typeof PlaceVerdict>;
  notice?: ReactNode;
  nights: ComponentProps<typeof NightsStrip>;
  nightsFailure?: ReactNode;
  alert?: ComponentProps<typeof AlertRow>;
  siteNames?: ReadonlyMap<number, string>;
  blocks: PoiBlockSlots;
  onClose?: () => void;
}

export function PlaceSheet({
  name,
  subtitle,
  arrival,
  verdict,
  notice,
  nights,
  nightsFailure,
  alert,
  siteNames,
  blocks,
  onClose,
}: PlaceSheetProps) {
  const [detent, setDetent] = useState<SheetDetent>(ARRIVAL_DETENT[arrival]);

  const selected = nights.days.find((day) => day.date === nights.selected) ?? null;
  const hasStay = nights.dates.length > 0 || alert !== undefined || nightsFailure !== undefined;
  const peekBlocks = slotsFor(blocks, PEEK_BLOCKS);
  const halfBlocks = slotsFor(blocks, HALF_BLOCKS);
  const fullBlocks = slotsFor(blocks, FULL_BLOCKS);
  const secondary = subtitle ? <p className="rt-place-sheet__subtitle">{subtitle}</p> : null;
  const named = (
    <div className="rt-place-sheet__compact">
      {secondary}
      <h2 className="rt-place-sheet__name">{name}</h2>
    </div>
  );

  return (
    <Sheet
      className="rt-place-sheet"
      data-testid={PLACE_SHEET_TESTID}
      detent={detent}
      onDetentChange={setDetent}
      label={name}
      {...(onClose ? { onClose } : null)}
    >
      <SheetHeader
        full={
          verdict ? (
            <>
              {secondary}
              <PlaceVerdict {...verdict} nameAs={NAME_HEADING} />
            </>
          ) : (
            named
          )
        }
        compact={named}
      />
      {notice ? <SheetSection from={PEEK_DETENT}>{notice}</SheetSection> : null}
      {hasStay ? (
        <SheetSection from={HALF_DETENT}>
          <div className="rt-place-sheet__stay">
            {nightsFailure ?? (
              <>
                <NightsStrip {...nights} />
                <NightSites day={selected} names={siteNames} />
              </>
            )}
            {alert ? <AlertRow {...alert} /> : null}
          </div>
        </SheetSection>
      ) : null}
      {peekBlocks ? (
        <SheetSection from={PEEK_DETENT}>
          <PoiPageShell blocks={peekBlocks} />
        </SheetSection>
      ) : null}
      {halfBlocks ? (
        <SheetSection from={HALF_DETENT}>
          <PoiPageShell blocks={halfBlocks} />
        </SheetSection>
      ) : null}
      {fullBlocks ? (
        <SheetSection from={FULL_DETENT}>
          <PoiPageShell blocks={fullBlocks} />
        </SheetSection>
      ) : null}
    </Sheet>
  );
}
