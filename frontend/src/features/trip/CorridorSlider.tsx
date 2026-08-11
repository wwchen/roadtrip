// How wide "along the route" is.
//
// This remains a controlled range input because it drives live map repainting.
import {
  CORRIDOR_MAX_MILES,
  CORRIDOR_MIN_MILES,
  CORRIDOR_STEP_MILES,
} from '@/stores/tripStore';

export interface CorridorSliderProps {
  miles: number;
  onChange: (miles: number) => void;
}

export function CorridorSlider({ miles, onChange }: CorridorSliderProps) {
  return (
    <div className="tb-corridor visible" id="tb-corridor">
      <label htmlFor="tb-corridor-range">Corridor</label>
      <input
        type="range"
        id="tb-corridor-range"
        min={CORRIDOR_MIN_MILES}
        max={CORRIDOR_MAX_MILES}
        step={CORRIDOR_STEP_MILES}
        value={miles}
        aria-label="Corridor radius in miles"
        // `onChange` fires per tick of the drag, which is deliberate: the corridor
        // fill repaints locally so the user sees it breathe, and the request behind
        // it is debounced in `useOnRoutePois`.
        onChange={(event) => onChange(Number(event.target.value))}
      />
      <span className="tb-corridor-value" id="tb-corridor-value">
        {miles} mi
      </span>
    </div>
  );
}
