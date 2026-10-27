import {
  createContext,
  useContext,
  useRef,
  type HTMLAttributes,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import './sheet.css';

export type SheetDetent = 'peek' | 'half' | 'full';

export const SHEET_DETENTS: readonly SheetDetent[] = ['peek', 'half', 'full'];

const DEFAULT_COMPACT_FROM: SheetDetent = 'half';
const DRAG_THRESHOLD_PX = 4;
const PRIMARY_BUTTON = 0;
const RAISE_KEY = 'ArrowUp';
const LOWER_KEY = 'ArrowDown';
const DRAG_HEIGHT_PROPERTY = '--rt-sheet-drag-height';
const DRAGGING_ATTRIBUTE = 'data-dragging';
const DETENT_RATIO_PROPERTY: Record<SheetDetent, string> = {
  peek: '--rt-sheet-peek',
  half: '--rt-sheet-half',
  full: '--rt-sheet-full',
};

const SheetContext = createContext<SheetDetent | null>(null);

function useSheetDetent(): SheetDetent {
  const detent = useContext(SheetContext);
  if (detent === null) {
    throw new Error('SheetHeader and SheetSection must be rendered inside a Sheet');
  }
  return detent;
}

const rank = (detent: SheetDetent): number => SHEET_DETENTS.indexOf(detent);

const atOrAbove = (current: SheetDetent, level: SheetDetent): boolean => rank(current) >= rank(level);

const stepped = (detent: SheetDetent, by: number): SheetDetent => {
  const next = Math.min(Math.max(rank(detent) + by, 0), SHEET_DETENTS.length - 1);
  return SHEET_DETENTS[next];
};

const tapped = (detent: SheetDetent): SheetDetent => (detent === 'full' ? 'peek' : stepped(detent, 1));

type DetentHeights = Record<SheetDetent, number>;

function measureDetents(root: HTMLElement): DetentHeights | null {
  const container = root.offsetParent?.clientHeight || window.innerHeight;
  const style = getComputedStyle(root);
  const heights = {} as DetentHeights;
  for (const detent of SHEET_DETENTS) {
    const ratio = parseFloat(style.getPropertyValue(DETENT_RATIO_PROPERTY[detent]));
    if (Number.isNaN(ratio)) return null;
    heights[detent] = ratio * container;
  }
  return heights;
}

function nearestDetent(heights: DetentHeights, height: number): SheetDetent {
  return SHEET_DETENTS.reduce((best, detent) =>
    Math.abs(heights[detent] - height) < Math.abs(heights[best] - height) ? detent : best,
  );
}

interface Drag {
  pointerId: number;
  startY: number;
  startHeight: number;
  heights: DetentHeights;
  height: number;
  moved: boolean;
}

export interface SheetProps extends HTMLAttributes<HTMLElement> {
  detent: SheetDetent;
  onDetentChange: (next: SheetDetent) => void;
  label: string;
  children?: ReactNode;
}

export function Sheet({ detent, onDetentChange, label, className, children, ...rest }: SheetProps) {
  const rootRef = useRef<HTMLElement>(null);
  const dragRef = useRef<Drag | null>(null);
  const suppressClickRef = useRef(false);

  const endDrag = () => {
    dragRef.current = null;
    rootRef.current?.removeAttribute(DRAGGING_ATTRIBUTE);
    rootRef.current?.style.removeProperty(DRAG_HEIGHT_PROPERTY);
  };

  const onPointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    suppressClickRef.current = false;
    if (event.button !== PRIMARY_BUTTON || !rootRef.current) return;
    const heights = measureDetents(rootRef.current);
    if (!heights) return;
    dragRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      startHeight: heights[detent],
      heights,
      height: heights[detent],
      moved: false,
    };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current;
    const root = rootRef.current;
    if (!drag || !root || drag.pointerId !== event.pointerId) return;
    const travelled = drag.startY - event.clientY;
    if (!drag.moved && Math.abs(travelled) < DRAG_THRESHOLD_PX) return;
    if (!drag.moved) {
      drag.moved = true;
      root.setAttribute(DRAGGING_ATTRIBUTE, '');
    }
    drag.height = Math.min(Math.max(drag.startHeight + travelled, drag.heights.peek), drag.heights.full);
    root.style.setProperty(DRAG_HEIGHT_PROPERTY, `${drag.height}px`);
  };

  const onPointerUp = (event: PointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    endDrag();
    if (!drag.moved) return;
    suppressClickRef.current = true;
    const next = nearestDetent(drag.heights, drag.height);
    if (next !== detent) onDetentChange(next);
  };

  const onClick = () => {
    if (suppressClickRef.current) {
      suppressClickRef.current = false;
      return;
    }
    onDetentChange(tapped(detent));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const by = event.key === RAISE_KEY ? 1 : event.key === LOWER_KEY ? -1 : 0;
    if (by === 0) return;
    event.preventDefault();
    const next = stepped(detent, by);
    if (next !== detent) onDetentChange(next);
  };

  return (
    <section
      {...rest}
      ref={rootRef}
      aria-label={label}
      data-detent={detent}
      className={className ? `rt-sheet ${className}` : 'rt-sheet'}
    >
      <button
        type="button"
        className="rt-sheet-grabber"
        aria-label={label}
        aria-expanded={detent !== 'peek'}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={endDrag}
        onClick={onClick}
        onKeyDown={onKeyDown}
      >
        <span className="rt-sheet-handle" aria-hidden="true" />
      </button>
      <div className="rt-sheet-body">
        <SheetContext.Provider value={detent}>{children}</SheetContext.Provider>
      </div>
    </section>
  );
}

export interface SheetHeaderProps {
  full: ReactNode;
  compact: ReactNode;
  compactFrom?: SheetDetent;
}

export function SheetHeader({ full, compact, compactFrom = DEFAULT_COMPACT_FROM }: SheetHeaderProps) {
  const detent = useSheetDetent();
  return <header className="rt-sheet-header">{atOrAbove(detent, compactFrom) ? compact : full}</header>;
}

export interface SheetSectionProps {
  from: SheetDetent;
  children?: ReactNode;
}

export function SheetSection({ from, children }: SheetSectionProps) {
  const detent = useSheetDetent();
  if (!atOrAbove(detent, from)) return null;
  return <div className="rt-sheet-section">{children}</div>;
}
