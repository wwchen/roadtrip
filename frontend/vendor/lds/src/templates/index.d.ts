// Types for the HTML binding.
//
// Every component is `(props) => string`, where the string is HTML. Slots take
// text (escaped for you) or `raw()` markup, so the unsafe path has to be chosen
// rather than reached by forgetting to escape.

/** Markup that has already been escaped, for composing into a slot. */
export interface RawHtml {
  __html: string;
}

/** A slot takes text (escaped) or `raw()` markup. */
export type Slot = string | number | RawHtml | null | undefined | false;

/** Escapes text for HTML. */
export declare function escapeHtml(value: unknown): string;

/**
 * Marks a string as already-safe HTML so it can be composed into a slot.
 * An unmarked string is always treated as text and escaped.
 */
export declare function raw(html: string): RawHtml;

/** Resolves a slot: `raw()` passes through, anything else is escaped text. */
export declare function slot(value: unknown): string;

/** Joins class names, dropping the falsy ones. */
export declare function cx(...parts: unknown[]): string;

/** Serialises a prop bag to HTML attributes. `tag` defers value/checked on form controls. */
export declare function attrs(props: Record<string, unknown> | null | undefined, tag?: string): string;

/** Serialises a style object to a CSS declaration list. */
export declare function styleAttr(style: Record<string, unknown> | string | null | undefined): string;

/** One `<use>` reference into the icon sprite. */
export declare function spriteSvg(name: string, iconHref?: string): string;

/** Builds a tree of elements and templates. Returns `raw()` markup. */
export declare function h(
  type: string | ((props: any) => string | RawHtml),
  props?: Record<string, unknown> | null,
  ...children: unknown[]
): RawHtml;

/** Renders a tree into an element. */
export declare function mount<T extends { innerHTML: string }>(container: T, tree: unknown): T;

// ---- shared vocabulary ------------------------------------------------------

export type Status = 'info' | 'success' | 'warning' | 'caution' | 'error';
export type Emphasis = 'plain' | 'subtle' | 'soft' | 'strong' | 'stark' | 'media';
export type Hue = 'red' | 'orange' | 'yellow' | 'green' | 'cyan' | 'blue' | 'violet' | 'pink' | 'gray';
export type Size = 'sm' | 'md' | 'lg';

/** Any extra prop is serialised as an HTML attribute on the root element. */
export interface HtmlProps {
  className?: string;
  id?: string;
  style?: Record<string, string | number> | string;
  [attr: string]: unknown;
}

// ---- components -------------------------------------------------------------

export interface AvatarProps extends HtmlProps {
  /** The hue and the initials are both derived from this. */
  name?: string;
  src?: string;
  alt?: string;
  size?: Size | 'xs' | 'xl';
  /** Overrides the hue derived from `name`. */
  hue?: Hue;
  ring?: boolean;
  iconHref?: string;
}
export declare function avatar(props?: AvatarProps): string;

export interface BannerProps extends HtmlProps {
  status?: Status;
  emphasis?: Emphasis;
  /** Full-bleed square system banner, rather than the rounded content banner. */
  page?: boolean;
  title?: Slot;
  children?: Slot;
  actions?: Slot;
  /** Overrides the status glyph. `null` suppresses it. */
  icon?: Slot;
  iconHref?: string;
  dismissible?: boolean;
  /** Accepted and ignored — bind the handler by delegation after mounting. */
  onDismiss?: unknown;
}
export declare function banner(props?: BannerProps): string;

export interface ButtonProps extends HtmlProps {
  variant?: 'primary' | 'secondary' | 'tertiary';
  size?: 'sm' | 'lg';
  iconOnly?: boolean;
  fab?: boolean;
  extended?: boolean;
  emphasis?: Emphasis;
  hue?: Hue;
  /** Second press of a destructive action. */
  armed?: boolean;
  /** A sprite name, or composed markup. */
  iconStart?: Slot;
  iconEnd?: Slot;
  subtitle?: Slot;
  iconHref?: string;
  /** Renders as an anchor. With `disabled`, the href is dropped. */
  href?: string;
  disabled?: boolean;
  children?: Slot;
}
export declare function button(props?: ButtonProps): string;

export interface ButtonGroupProps extends HtmlProps {
  children?: Slot;
  /** Turns the group into a conversion bar. */
  detail?: Slot;
  detailNote?: Slot;
  orientation?: 'horizontal' | 'vertical';
  width?: 'hug' | 'fill';
  align?: 'start' | 'center' | 'end' | 'split';
  stackOnMobile?: boolean;
}
export declare function buttonGroup(props?: ButtonGroupProps): string;

export interface CardProps extends HtmlProps {
  kicker?: Slot;
  title?: Slot;
  body?: Slot;
  meta?: Slot;
  actions?: Slot;
  emphasis?: Emphasis;
  hue?: Hue;
  /** Renders a real button, with Space/Enter and a reported pressed state. */
  selectable?: boolean;
  selected?: boolean;
  disabled?: boolean;
  onClick?: unknown;
  children?: Slot;
}
export declare function card(props?: CardProps): string;

export interface CheckboxProps extends HtmlProps {
  label?: Slot;
  checked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
}
export declare function checkbox(props?: CheckboxProps): string;

export interface ChipProps extends HtmlProps {
  children?: Slot;
  selected?: boolean;
  size?: 'sm' | 'lg';
  icon?: Slot;
  caret?: Slot;
  /** Present ⇒ renders as a static span with a remove button. */
  onRemove?: unknown;
  removeLabel?: string;
  iconHref?: string;
  onClick?: unknown;
}
export declare function chip(props?: ChipProps): string;

export interface CodeFieldProps extends HtmlProps {
  label?: Slot;
  help?: Slot;
  error?: Slot;
  /** `true` renders "Verified". */
  success?: Slot | true;
  /** `true` renders "Checking…". */
  verifying?: Slot | true;
  length?: number;
  /** Draws a gap after this many boxes. */
  groupAfter?: number;
  size?: 'sm';
  value?: string;
  iconHref?: string;
}
export declare function codeField(props?: CodeFieldProps): string;

export interface EmptyStateProps extends HtmlProps {
  /** A sprite name, or composed markup. */
  icon?: Slot;
  /** A src, or composed markup. */
  image?: Slot;
  imageAlt?: string;
  expressive?: boolean;
  iconHref?: string;
  title?: Slot;
  body?: Slot;
  actions?: Slot;
}
export declare function emptyState(props?: EmptyStateProps): string;

export interface IconProps extends HtmlProps {
  name: string;
  size?: number;
  /** Overrides the sprite for this icon only. */
  href?: string;
}
export declare function icon(props?: IconProps): string;

export interface InlineProps extends HtmlProps {
  status?: Status;
  /** Overrides the status glyph. `null` suppresses it. */
  icon?: Slot;
  iconHref?: string;
  children?: Slot;
}
export declare function inline(props?: InlineProps): string;

export interface LinkProps extends HtmlProps {
  children?: Slot;
  href?: string;
  variant?: 'quiet' | 'standalone';
  /** A sprite name, composed markup, or `null` to suppress the chevron. */
  iconEnd?: Slot;
  iconHref?: string;
}
export declare function link(props?: LinkProps): string;

export interface MenuItem {
  label?: Slot;
  icon?: Slot;
  hint?: Slot;
  danger?: boolean;
  disabled?: boolean;
  separator?: boolean;
}
export interface MenuProps extends HtmlProps {
  items?: MenuItem[];
}
export declare function menu(props?: MenuProps): string;

export interface ModalProps extends HtmlProps {
  title?: Slot;
  children?: Slot;
  actions?: Slot;
  cancel?: Slot;
  onClose?: unknown;
  /** Present ⇒ draws the back affordance, which pops ONE level of a flow. */
  onBack?: unknown;
  size?: Size;
  sheet?: boolean;
  side?: boolean;
  largeTitle?: boolean;
  iconHref?: string;
}
export declare function modal(props?: ModalProps): string;

export interface NavProps extends HtmlProps {
  variant?: 'brand' | 'bar';
  logo?: Slot;
  links?: Slot;
  title?: Slot;
  subtitle?: Slot;
  onBack?: unknown;
  backLabel?: string;
  actions?: Slot;
  sticky?: boolean;
  scrolled?: boolean;
  iconHref?: string;
  children?: Slot;
}
export declare function nav(props?: NavProps): string;

export interface RadioProps extends HtmlProps {
  label?: Slot;
  name?: string;
  checked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
}
export declare function radio(props?: RadioProps): string;

export interface RowProps extends HtmlProps {
  lead?: Slot;
  title?: Slot;
  subtitle?: Slot;
  trail?: Slot;
  /** `true` draws chevron-right; markup is used as given. */
  chevron?: Slot | true;
  selected?: boolean;
  iconHref?: string;
  compact?: boolean;
  roomy?: boolean;
  /** Present ⇒ renders as an anchor. */
  href?: string;
}
export declare function row(props?: RowProps): string;

export type SelectOption = string | { value?: string; label?: Slot };
export interface SelectOptGroup { label?: string; options?: SelectOption[] }
export interface SelectProps extends HtmlProps {
  label?: Slot;
  help?: Slot;
  error?: Slot;
  required?: boolean;
  options?: (SelectOption | SelectOptGroup)[];
}
export declare function select(props?: SelectProps): string;

export interface SegmentedOption {
  value?: string;
  label?: Slot;
  icon?: string;
  disabled?: boolean;
}
export interface SegmentedControlProps extends HtmlProps {
  options?: (string | SegmentedOption)[];
  value?: string;
  /** The radios only behave as one group if they share this. */
  name?: string;
  size?: 'sm' | 'lg';
  full?: boolean;
  iconsOnly?: boolean;
  label?: string;
  iconHref?: string;
}
export declare function segmentedControl(props?: SegmentedControlProps): string;

export interface SkeletonProps extends HtmlProps {
  variant?: 'text' | 'title' | 'circle' | 'block';
  last?: boolean;
}
export declare function skeleton(props?: SkeletonProps): string;

export interface TableColumn { key: string; label?: Slot }
export interface TableProps extends HtmlProps {
  columns?: TableColumn[];
  rows?: Record<string, Slot>[];
}
export declare function table(props?: TableProps): string;

export interface TabItem {
  id?: string;
  label?: Slot;
  icon?: string;
  iconHref?: string;
  /** Renders a group heading instead of a tab. */
  section?: Slot;
}
export interface TabsProps extends HtmlProps {
  tabs?: TabItem[];
  active?: string;
}
export declare function tabs(props?: TabsProps): string;

export interface TagProps extends HtmlProps {
  children?: Slot;
  hue?: Hue;
  status?: Status;
  emphasis?: Emphasis;
  size?: 'sm';
  interactive?: boolean;
  inactive?: boolean;
  icon?: Slot;
  dot?: boolean;
}
export declare function tag(props?: TagProps): string;

export interface TextFieldProps extends HtmlProps {
  label?: Slot;
  help?: Slot;
  error?: Slot;
  required?: boolean;
  /** A sprite name, or composed markup. */
  iconStart?: Slot;
  iconEnd?: Slot;
  endAction?: { icon?: string; label?: string; onClick?: unknown };
  /** A whole control joined into the box — a dial-code select, typically. */
  prefix?: Slot;
  iconHref?: string;
  type?: string;
  placeholder?: string;
  value?: string;
}
export declare function textField(props?: TextFieldProps): string;

export interface TextareaProps extends HtmlProps {
  label?: Slot;
  help?: Slot;
  error?: Slot;
  required?: boolean;
  maxLength?: number;
  showCount?: boolean;
  value?: string;
  rows?: number;
  placeholder?: string;
}
export declare function textarea(props?: TextareaProps): string;

export interface ToastProps extends HtmlProps {
  status?: Status;
  title?: Slot;
  children?: Slot;
  actions?: Slot;
  dismissible?: boolean;
  onDismiss?: unknown;
  dismissLabel?: string;
  icon?: Slot;
  iconHref?: string;
}
export declare function toast(props?: ToastProps): string;

export interface ToggleProps extends HtmlProps {
  label?: Slot;
  help?: Slot;
  checked?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
}
export declare function toggle(props?: ToggleProps): string;

export interface TooltipProps extends HtmlProps {
  label?: Slot;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  /** The trigger, as markup. */
  children?: Slot;
}
export declare function tooltip(props?: TooltipProps): string;
