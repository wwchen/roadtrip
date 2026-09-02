import { watchCopy } from '@/lib/strings';
import { Icon } from '@ui';

export interface WatchPanelHeadProps {
  title?: string;
  subtitle?: string;
  onClose?: (() => void) | null;
}

/** The title block and close affordance both watch panels share. */
export function WatchPanelHead({ title, subtitle, onClose }: WatchPanelHeadProps) {
  return (
    <div className="rt-watch-editor-head">
      <div>
        {title ? <div className="rt-watch-editor-title">{title}</div> : null}
        {subtitle ? <div className="rt-watch-editor-subtitle">{subtitle}</div> : null}
      </div>
      {onClose ? (
        <button type="button" className="rt-watch-editor-icon" aria-label={watchCopy.close} onClick={onClose}>
          <Icon name="close" aria-hidden="true" />
        </button>
      ) : null}
    </div>
  );
}
