import { alertCopy, dayCopy, gateCopy } from '@/lib/strings';
import { longDayLabel } from '@/lib/week-labels';
import { Button, Row } from '@ui';
import type { WatchGate } from './watch-gate';

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
        aria-label={pending ? alertCopy.creating : `${alertCopy.create} for ${label}`}
        onClick={() => onCreate(night)}
      >
        {pending ? alertCopy.creating : alertCopy.create}
      </Button>
    ) : props.gate === 'signed-out' ? (
      <Button size="sm" onClick={props.onSignIn}>
        {gateCopy.signIn}
      </Button>
    ) : undefined;

  return <Row compact title={label} subtitle={SUBTITLE[props.gate]} trail={action} />;
}
