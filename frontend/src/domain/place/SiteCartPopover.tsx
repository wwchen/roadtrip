import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react';
import { Icon } from '@ui';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { bookingCopy, gateCopy, placeCopy } from '@/lib/strings';
import { useDismiss } from '@/lib/use-dismiss';
import { cartActionFor, type CartAction, type CartActionCell } from './cart-action';
import type { ReachableCartGate } from './cart-gate';
import './site-cart.css';

const POPOVER_WIDTH_PX = 248;
const POPOVER_MARGIN_PX = 8;
const POPOVER_ANCHOR_GAP_PX = 6;
const POPOVER_WIDTH_PROPERTY = '--rt-site-cart-width';

const NO_HEIGHT_YET = 0;

const CART_ICON = 'cart-add';
const HELD_ICON = 'cart-check';
const DISMISS_KEY = 'Escape';

export interface SiteCartPopoverProps {
  anchor: HTMLElement;
  cell: CartActionCell;
  site: string;
  night: string;
  gate: ReachableCartGate;
  action: CartAction | null;
  onClose: () => void;
}

function hintFor(gate: ReachableCartGate): string | null {
  if (gate.state === 'signed_out') return gateCopy.cartSignedOut;
  if (gate.state === 'no_credentials') return gateCopy.cartNoCredentials(gate.holder);
  return null;
}

function runStep(gate: ReachableCartGate, cell: CartActionCell): void {
  if (gate.state === 'ready') gate.onAddToCart(cell.campsiteId, cell.date);
  else if (gate.state === 'signed_out') gate.onSignIn();
  else gate.onOpenSettings();
}

export function SiteCartPopover({
  anchor,
  cell,
  site,
  night,
  gate,
  action,
  onClose,
}: SiteCartPopoverProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const stepRef = useRef<HTMLButtonElement>(null);
  const focused = useRef(false);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);

  const mine = cartActionFor(action, cell.campsiteId, cell.date);
  const pending = mine?.kind === 'pending';
  const held = mine?.kind === 'held' ? mine : null;
  const failed = mine?.kind === 'failed' ? mine : null;
  const hint = hintFor(gate);

  const reposition = useCallback(() => {
    if (!anchor.isConnected) {
      onClose();
      return;
    }
    const next = positionFor(
      anchor,
      hostRef.current?.getBoundingClientRect().height ?? NO_HEIGHT_YET,
    );
    setPosition((current) =>
      current && current.top === next.top && current.left === next.left ? current : next,
    );
  }, [anchor, onClose]);

  useLayoutEffect(reposition, [reposition, mine?.kind]);

  useEffect(() => {
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
    };
  }, [reposition]);

  useEffect(() => {
    if (position === null || focused.current) return;
    focused.current = true;
    stepRef.current?.focus();
  }, [position]);

  useDismiss(hostRef, onClose);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== DISMISS_KEY) return;
    event.preventDefault();
    event.stopPropagation();
    anchor.focus();
    onClose();
  };

  const style = {
    [POPOVER_WIDTH_PROPERTY]: `${POPOVER_WIDTH_PX}px`,
    top: position?.top ?? NO_HEIGHT_YET,
    left: position?.left ?? NO_HEIGHT_YET,
    visibility: position ? 'visible' : 'hidden',
  } as CSSProperties;

  return (
    <div
      className="rt-site-cart"
      ref={hostRef}
      role="group"
      aria-label={bookingCopy.addToCart}
      style={style}
      onKeyDown={onKeyDown}
    >
      <p className="rt-site-cart__which">
        <span className="rt-site-cart__site">{site}</span>
        {placeCopy.factsSeparator}
        <span>{night}</span>
      </p>

      {held ? (
        <div className="rt-site-cart__held" role="status">
          <p className="rt-site-cart__title">
            <Icon name={HELD_ICON} className="rt-site-cart__icon" aria-hidden="true" />
            {bookingCopy.heldTitle(held.holder)}
          </p>
          <p className="rt-site-cart__hint">{bookingCopy.checkOutSoon(held.holder)}</p>
          <a
            className="rt-site-cart__link"
            href={held.cartUrl}
            target="_blank"
            rel="noreferrer noopener"
          >
            {bookingCopy.openCart(held.holder)}
          </a>
        </div>
      ) : (
        <>
          <button
            type="button"
            ref={stepRef}
            className={`rt-site-cart__step${gate.state === 'ready' ? ' rt-site-cart__step--ready' : ''}`}
            disabled={pending}
            aria-busy={pending}
            aria-label={hint ? `${bookingCopy.addToCart} — ${hint}` : bookingCopy.addToCart}
            onClick={() => runStep(gate, cell)}
          >
            <Icon name={CART_ICON} className="rt-site-cart__icon" aria-hidden="true" />
            <span className="rt-site-cart__step-text">
              <span>{bookingCopy.addToCart}</span>
              {hint ? <span className="rt-site-cart__hint">{hint}</span> : null}
            </span>
          </button>
          {pending ? (
            <p className="rt-site-cart__hint" role="status">
              {bookingCopy.holdRunning}
            </p>
          ) : null}
          {failed ? (
            <div className="rt-site-cart__failed" role="alert">
              <p className="rt-site-cart__title">{bookingCopy.holdFailed}</p>
              <p className="rt-site-cart__hint">
                {settingsErrorMessage(failed.code, failed.holder)}
              </p>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}

function positionFor(anchor: HTMLElement, popoverHeight: number): { top: number; left: number } {
  const rect = anchor.getBoundingClientRect();
  const viewport = { width: window.innerWidth, height: window.innerHeight };

  const minLeft = POPOVER_MARGIN_PX;
  const maxLeft = viewport.width - POPOVER_WIDTH_PX - POPOVER_MARGIN_PX;
  const minTop = POPOVER_MARGIN_PX;
  const maxTop = viewport.height - popoverHeight - POPOVER_MARGIN_PX;

  const below = rect.bottom + POPOVER_ANCHOR_GAP_PX;
  const above = rect.top - popoverHeight - POPOVER_ANCHOR_GAP_PX;
  const top =
    popoverHeight > NO_HEIGHT_YET && below > maxTop && above >= minTop
      ? above
      : clamp(below, minTop, maxTop);

  return { top, left: clamp(rect.left, minLeft, maxLeft) };
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)));
}
