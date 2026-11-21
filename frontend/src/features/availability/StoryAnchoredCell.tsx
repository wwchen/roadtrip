import { useEffect, useRef, useState, type ReactNode } from 'react';

const ZION_THEME_CLASS = 'theme-roadtrip-zion';
const CELL_WIDTH_PX = 66;
const CELL_HEIGHT_PX = 40;
const FRAME_PADDING_PX = 24;
const DEFAULT_FRAME_HEIGHT_PX = 260;

export interface StoryAnchoredCellProps {
  label: string;
  minHeight?: number;
  children: (anchor: HTMLElement) => ReactNode;
}

export function StoryAnchoredCell({ label, minHeight = DEFAULT_FRAME_HEIGHT_PX, children }: StoryAnchoredCellProps) {
  const ref = useRef<HTMLButtonElement>(null);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
    setAnchor(ref.current);
  }, []);

  return (
    <div style={{ padding: FRAME_PADDING_PX, minHeight }}>
      <button
        ref={ref}
        type="button"
        className="cg-site-matrix-cell-button is-armed"
        style={{ width: CELL_WIDTH_PX, height: CELL_HEIGHT_PX }}
      >
        {label}
      </button>
      {anchor ? children(anchor) : null}
    </div>
  );
}
