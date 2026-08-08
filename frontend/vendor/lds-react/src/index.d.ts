// Types for @lew/lds-react.
//
// Every `Slot` prop from @lew/lds's own types (title, children, actions, …)
// widens to accept a React node here, on top of everything it already took
// (text, `raw()` markup) — see runtime.jsx's `toSlot` for how a node gets
// flattened to markup before the vanilla template ever sees it. List-shaped
// props (Menu's `items`, Table's `rows`, …) are NOT widened — their entries
// stay whatever @lew/lds already typed them as (see components.jsx's
// per-component comments on that).
import type { ReactNode, ForwardRefExoticComponent, RefAttributes, ReactElement, CSSProperties } from 'react';
import type {
  RawHtml, Status, Emphasis, Hue, Size, HtmlProps,
  AvatarProps as VAvatarProps,
  MenuItem, SelectOption, SelectOptGroup, SegmentedOption,
  TableColumn, TabItem,
} from '@lew/lds/templates';

export type ReactSlot = ReactNode | RawHtml;

/** Flattens a React node to `raw()` markup; passes everything else through. */
export declare function toSlot(value: unknown): unknown;

interface ReactHtmlProps extends Omit<HtmlProps, 'className' | 'style'> {
  className?: string;
  style?: CSSProperties;
}

// ---- stateless components ----------------------------------------------------

export type AvatarProps = VAvatarProps & RefAttributes<HTMLSpanElement>;
export declare const Avatar: ForwardRefExoticComponent<AvatarProps>;

export interface BannerProps extends ReactHtmlProps {
  status?: Status;
  emphasis?: Emphasis;
  page?: boolean;
  title?: ReactSlot;
  children?: ReactSlot;
  actions?: ReactSlot;
  icon?: ReactSlot;
  iconHref?: string;
  dismissible?: boolean;
  onDismiss?: (e: MouseEvent) => void;
}
export declare const Banner: ForwardRefExoticComponent<BannerProps & RefAttributes<HTMLDivElement>>;

export interface ButtonProps extends ReactHtmlProps {
  variant?: 'primary' | 'secondary' | 'tertiary';
  size?: 'sm' | 'lg';
  iconOnly?: boolean;
  fab?: boolean;
  extended?: boolean;
  emphasis?: Emphasis;
  hue?: Hue;
  armed?: boolean;
  iconStart?: ReactSlot;
  iconEnd?: ReactSlot;
  subtitle?: ReactSlot;
  iconHref?: string;
  href?: string;
  disabled?: boolean;
  children?: ReactSlot;
  onClick?: (e: MouseEvent) => void;
}
export declare const Button: ForwardRefExoticComponent<ButtonProps & RefAttributes<HTMLButtonElement | HTMLAnchorElement>>;

export interface ButtonGroupProps extends ReactHtmlProps {
  children?: ReactSlot;
  detail?: ReactSlot;
  detailNote?: ReactSlot;
  orientation?: 'horizontal' | 'vertical';
  width?: 'hug' | 'fill';
  align?: 'start' | 'center' | 'end' | 'split';
  stackOnMobile?: boolean;
}
export declare const ButtonGroup: ForwardRefExoticComponent<ButtonGroupProps & RefAttributes<HTMLDivElement>>;

export interface CardProps extends ReactHtmlProps {
  kicker?: ReactSlot;
  title?: ReactSlot;
  body?: ReactSlot;
  meta?: ReactSlot;
  actions?: ReactSlot;
  emphasis?: Emphasis;
  hue?: Hue;
  selectable?: boolean;
  selected?: boolean;
  disabled?: boolean;
  children?: ReactSlot;
  onClick?: (e: MouseEvent) => void;
}
export declare const Card: ForwardRefExoticComponent<CardProps & RefAttributes<HTMLDivElement | HTMLButtonElement>>;

export interface CheckboxProps extends ReactHtmlProps {
  label?: ReactSlot;
  checked?: boolean;
  defaultChecked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  onChange?: (e: Event) => void;
}
export declare const Checkbox: ForwardRefExoticComponent<CheckboxProps & RefAttributes<HTMLLabelElement>>;

export interface ChipProps extends ReactHtmlProps {
  children?: ReactSlot;
  selected?: boolean;
  size?: 'sm' | 'lg';
  icon?: ReactSlot;
  caret?: ReactSlot;
  onRemove?: (e: MouseEvent) => void;
  removeLabel?: string;
  iconHref?: string;
  onClick?: (e: MouseEvent) => void;
}
export declare const Chip: ForwardRefExoticComponent<ChipProps & RefAttributes<HTMLElement>>;

export interface EmptyStateProps extends ReactHtmlProps {
  icon?: ReactSlot;
  image?: ReactSlot;
  imageAlt?: string;
  expressive?: boolean;
  iconHref?: string;
  title?: ReactSlot;
  body?: ReactSlot;
  actions?: ReactSlot;
}
export declare const EmptyState: ForwardRefExoticComponent<EmptyStateProps & RefAttributes<HTMLDivElement>>;

export interface IconProps extends ReactHtmlProps {
  name: string;
  size?: number;
  href?: string;
}
export declare const Icon: ForwardRefExoticComponent<IconProps & RefAttributes<SVGSVGElement>>;

export interface InlineProps extends ReactHtmlProps {
  status?: Status;
  icon?: ReactSlot;
  iconHref?: string;
  children?: ReactSlot;
}
export declare const Inline: ForwardRefExoticComponent<InlineProps & RefAttributes<HTMLSpanElement>>;

export interface LinkProps extends ReactHtmlProps {
  children?: ReactSlot;
  href?: string;
  variant?: 'quiet' | 'standalone';
  iconEnd?: ReactSlot;
  iconHref?: string;
  onClick?: (e: MouseEvent) => void;
}
export declare const Link: ForwardRefExoticComponent<LinkProps & RefAttributes<HTMLAnchorElement>>;

export interface MenuProps extends ReactHtmlProps {
  /** `label`/`icon`/`hint` accept a React node, same as a flat slot prop. */
  items?: MenuItem[];
}
export declare const Menu: ForwardRefExoticComponent<MenuProps & RefAttributes<HTMLDivElement>>;

export interface ModalProps extends ReactHtmlProps {
  title?: ReactSlot;
  children?: ReactSlot;
  actions?: ReactSlot;
  cancel?: ReactSlot;
  onClose?: (e: MouseEvent) => void;
  onBack?: (e: MouseEvent) => void;
  size?: Size;
  sheet?: boolean;
  side?: boolean;
  largeTitle?: boolean;
  iconHref?: string;
}
export declare const Modal: ForwardRefExoticComponent<ModalProps & RefAttributes<HTMLDivElement>>;

export interface NavProps extends ReactHtmlProps {
  variant?: 'brand' | 'bar';
  logo?: ReactSlot;
  links?: ReactSlot;
  title?: ReactSlot;
  subtitle?: ReactSlot;
  onBack?: (e: MouseEvent) => void;
  backLabel?: string;
  actions?: ReactSlot;
  sticky?: boolean;
  scrolled?: boolean;
  iconHref?: string;
  children?: ReactSlot;
}
export declare const Nav: ForwardRefExoticComponent<NavProps & RefAttributes<HTMLElement>>;

export interface RadioProps extends ReactHtmlProps {
  label?: ReactSlot;
  name?: string;
  checked?: boolean;
  defaultChecked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  onChange?: (e: Event) => void;
}
export declare const Radio: ForwardRefExoticComponent<RadioProps & RefAttributes<HTMLLabelElement>>;

export interface RowProps extends ReactHtmlProps {
  lead?: ReactSlot;
  title?: ReactSlot;
  subtitle?: ReactSlot;
  trail?: ReactSlot;
  chevron?: ReactSlot | true;
  selected?: boolean;
  iconHref?: string;
  compact?: boolean;
  roomy?: boolean;
  href?: string;
  onClick?: (e: MouseEvent) => void;
}
export declare const Row: ForwardRefExoticComponent<RowProps & RefAttributes<HTMLElement>>;

export interface SelectProps extends ReactHtmlProps {
  label?: ReactSlot;
  help?: ReactSlot;
  error?: ReactSlot;
  required?: boolean;
  /** A top-level option's `label` accepts a React node. A group's own
   * `label` is a plain string in the vanilla type (not a slot); an option
   * nested inside a group's `options` is not reached — see README.md. */
  options?: (SelectOption | SelectOptGroup)[];
  value?: string;
  defaultValue?: string;
  onChange?: (e: Event) => void;
}
export declare const Select: ForwardRefExoticComponent<SelectProps & RefAttributes<HTMLDivElement>>;

export interface SkeletonProps extends ReactHtmlProps {
  variant?: 'text' | 'title' | 'circle' | 'block';
  last?: boolean;
}
export declare const Skeleton: ForwardRefExoticComponent<SkeletonProps & RefAttributes<HTMLSpanElement>>;

export interface TableProps extends ReactHtmlProps {
  /** A column's `label` accepts a React node. */
  columns?: TableColumn[];
  /** Every cell value accepts a React node (e.g. a `<Tag>` for a status column). */
  rows?: Record<string, unknown>[];
}
export declare const Table: ForwardRefExoticComponent<TableProps & RefAttributes<HTMLTableElement>>;

export interface TabsProps extends ReactHtmlProps {
  /** `label`/`section` accept a React node, same as a flat slot prop. */
  tabs?: TabItem[];
  active?: string;
}
export declare const Tabs: ForwardRefExoticComponent<TabsProps & RefAttributes<HTMLDivElement>>;

export interface TagProps extends ReactHtmlProps {
  children?: ReactSlot;
  hue?: Hue;
  status?: Status;
  emphasis?: Emphasis;
  size?: 'sm';
  interactive?: boolean;
  inactive?: boolean;
  icon?: ReactSlot;
  dot?: boolean;
}
export declare const Tag: ForwardRefExoticComponent<TagProps & RefAttributes<HTMLSpanElement>>;

export interface TextFieldProps extends ReactHtmlProps {
  label?: ReactSlot;
  help?: ReactSlot;
  error?: ReactSlot;
  required?: boolean;
  iconStart?: ReactSlot;
  iconEnd?: ReactSlot;
  endAction?: { icon?: string; label?: string; onClick?: (e: MouseEvent) => void };
  prefix?: ReactSlot;
  iconHref?: string;
  type?: string;
  placeholder?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (e: Event) => void;
}
export declare const TextField: ForwardRefExoticComponent<TextFieldProps & RefAttributes<HTMLDivElement>>;

export interface ToggleProps extends ReactHtmlProps {
  label?: ReactSlot;
  help?: ReactSlot;
  checked?: boolean;
  defaultChecked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  onChange?: (e: Event) => void;
}
export declare const Toggle: ForwardRefExoticComponent<ToggleProps & RefAttributes<HTMLDivElement>>;

// ---- stateful components (controller-backed) ---------------------------------

export interface CodeFieldProps extends ReactHtmlProps {
  label?: ReactSlot;
  help?: ReactSlot;
  error?: ReactSlot;
  success?: ReactSlot | true;
  verifying?: ReactSlot | true;
  length?: number;
  groupAfter?: number;
  size?: 'sm';
  value?: string;
  defaultValue?: string;
  iconHref?: string;
  /** Called with the resolved digit string — not a native event. */
  onChange?: (code: string) => void;
}
export declare const CodeField: ForwardRefExoticComponent<CodeFieldProps & RefAttributes<HTMLDivElement>>;

export interface SegmentedControlProps extends ReactHtmlProps {
  options?: (string | SegmentedOption)[];
  value?: string;
  defaultValue?: string;
  name?: string;
  size?: 'sm' | 'lg';
  full?: boolean;
  iconsOnly?: boolean;
  label?: string;
  iconHref?: string;
  /** Called with the selected value — not a native event. */
  onChange?: (value: string) => void;
}
export declare const SegmentedControl: ForwardRefExoticComponent<SegmentedControlProps & RefAttributes<HTMLDivElement>>;

export interface TextareaProps extends ReactHtmlProps {
  label?: ReactSlot;
  help?: ReactSlot;
  error?: ReactSlot;
  required?: boolean;
  maxLength?: number;
  showCount?: boolean;
  value?: string;
  defaultValue?: string;
  rows?: number;
  placeholder?: string;
  onChange?: (e: Event) => void;
}
export declare const Textarea: ForwardRefExoticComponent<TextareaProps & RefAttributes<HTMLDivElement>>;

export interface TooltipProps extends ReactHtmlProps {
  label?: ReactSlot;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  /** The trigger — a React node composes via a real portal, so an
   * interactive child (e.g. a `<Button onClick>` passed here) keeps its
   * own handler. */
  children?: ReactSlot;
}
export declare const Tooltip: ForwardRefExoticComponent<TooltipProps & RefAttributes<HTMLSpanElement>>;

// ---- toast ---------------------------------------------------------------

/** The presentational half — one message rendered in place, for a static
 * composition. For the real, interactive, queue-managed usage, see
 * `ToastProvider`/`useToast` below. */
export interface ToastProps extends ReactHtmlProps {
  status?: Status;
  title?: ReactSlot;
  children?: ReactSlot;
  actions?: ReactSlot;
  dismissible?: boolean;
  onDismiss?: (e: MouseEvent) => void;
  dismissLabel?: string;
  icon?: ReactSlot;
  iconHref?: string;
}
export declare const Toast: ForwardRefExoticComponent<ToastProps & RefAttributes<HTMLDivElement>>;

// ---- toast provider + hook (not a per-instance component) --------------------

export interface ToastProviderProps {
  children?: ReactNode;
  placement?: 'top' | 'bottom';
  duration?: number;
  max?: number;
  iconHref?: string;
}
/** Read once at mount, like the `mountToasts` it wraps — has no `update()`. */
export declare function ToastProvider(props: ToastProviderProps): ReactElement;

export interface ToastOptions {
  id?: string;
  status?: Status;
  title?: ReactSlot;
  children?: ReactSlot;
  actions?: ReactSlot;
  dismissible?: boolean;
  dismissLabel?: string;
  icon?: ReactSlot;
  /** Overrides the duration configured on `<ToastProvider>`; `0` persists until dismissed. */
  duration?: number;
}
export interface ToastApi {
  /** Raises a toast; returns its id. Re-raising an id replaces the message rather than stacking it. */
  toast(options: ToastOptions | string): string;
  dismiss(id: string): void;
}
export declare function useToast(): ToastApi;

// ---- re-exported from @lew/lds ------------------------------------------------

export {
  setIconSprite, getIconSprite, resolveSprite, STATUS_ICON,
  hueForName, initialsForName, DIAL_CODES, dialOptions,
} from '@lew/lds';
export type { Status, Emphasis, Hue, Size, RawHtml } from '@lew/lds/templates';
