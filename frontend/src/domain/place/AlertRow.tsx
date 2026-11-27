import { alertCopy, dayCopy, gateCopy, placeCopy } from '@/lib/strings';
import { longDayLabel } from '@/lib/week-labels';
import { Button } from '@ui';
import type { WatchGate } from './watch-gate';
import './alert-row.css';

interface AlertRowBase {
  night: string;
  pending?: boolean;
  onCreate: (night: string) => void;
}

export type AlertRowProps = AlertRowBase &
  (
    | { gate: Exclude<WatchGate, 'signed-out'>; onSignIn?: never }
    | { gate: 'signed-out'; onSignIn: () => void }
  );

const SUBTITLE = {
  ready: alertCopy.offer,
  'signed-out': gateCopy.watchSignedOut,
  blocked: dayCopy.unsupported,
} satisfies Record<WatchGate, string>;

export function AlertRow(props: AlertRowProps) {
  const { night, pending = false, onCreate } = props;
  const label = longDayLabel(night);

  const action =
    props.gate === 'ready' ? (
      <Button
        size="sm"
        disabled={pending}
        aria-busy={pending}
        aria-label={pending ? alertCopy.creatingFor(label) : alertCopy.createFor(label)}
        onClick={() => onCreate(night)}
      >
        {pending ? alertCopy.creating : alertCopy.create}
      </Button>
    ) : props.gate === 'signed-out' ? (
      <Button size="sm" onClick={props.onSignIn}>
        {gateCopy.signIn}
      </Button>
    ) : undefined;

  return (
    <div className="rt-alert-offer">
      <p className="rt-alert-offer__line">
        <span className="rt-alert-offer__night">{label}</span>
        {placeCopy.factsSeparator}
        <span className="rt-alert-offer__reason">{SUBTITLE[props.gate]}</span>
      </p>
      {action}
    </div>
  );
}
