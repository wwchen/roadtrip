// What a signed-out visitor gets where the watch editor would be.
//
// The same shell, in the same place, so signing in swaps the contents rather than
// moving the surface. It exists so the grid can offer a reserved night at all:
// before it, a cell nobody could act on was rendered as inert text, and the
// campground looked like one that simply does not do alerts.
import { gateCopy } from '@/lib/strings';
import { WatchPanelHead } from './WatchPanelHead';

export interface WatchSignInGateProps {
  /** Matches the editor's own title, so the two read as one surface. */
  title: string;
  subtitle: string;
  onSignIn: () => void;
  onClose: () => void;
}

export function WatchSignInGate({ title, subtitle, onSignIn, onClose }: WatchSignInGateProps) {
  return (
    <div className="rt-watch-editor" role="group" aria-label="Availability watch sign-in">
      <WatchPanelHead title={title} subtitle={subtitle} onClose={onClose} />
      <p className="rt-watch-editor-gate-text">{gateCopy.watchSignedOut}</p>
      <div className="rt-watch-editor-actions rt-watch-editor-actions--stretch">
        <button type="button" className="rt-watch-editor-save" onClick={onSignIn}>
          {gateCopy.signIn}
        </button>
      </div>
    </div>
  );
}
