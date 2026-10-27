import { alertCopy, dayCopy, gateCopy } from '@/lib/strings';
import { longDayLabel } from '@/lib/week-labels';
import { Button, Row } from '@ui';
import type { WatchGate } from './watch-gate';

export interface AlertRowProps {
  night: string;
  gate: WatchGate;
  pending?: boolean;
  onCreate: (night: string) => void;
  onSignIn?: () => void;
}

const SUBTITLE = {
  ready: alertCopy.offer,
  'signed-out': gateCopy.watchSignedOut,
  blocked: dayCopy.unsupported,
} satisfies Record<WatchGate, string>;

export function AlertRow({ night, gate, pending = false, onCreate, onSignIn }: AlertRowProps) {
  const label = longDayLabel(night);

  const action =
    gate === 'ready' ? (
      <Button
        size="sm"
        disabled={pending}
        aria-busy={pending}
        aria-label={pending ? alertCopy.creating : `${alertCopy.create} for ${label}`}
        onClick={() => onCreate(night)}
      >
        {pending ? alertCopy.creating : alertCopy.create}
      </Button>
    ) : gate === 'signed-out' ? (
      <Button size="sm" onClick={onSignIn}>
        {gateCopy.signIn}
      </Button>
    ) : undefined;

  return <Row compact title={label} subtitle={SUBTITLE[gate]} trail={action} />;
}
