// Controllers — the behaviour the templates cannot carry.
//
// Every one takes the same shape, so an LDS component drops into a codebase that
// already mounts its own components that way with no adapter in between.
import type {
  CodeFieldProps, SegmentedControlProps, TextareaProps, ToastProps, TooltipProps, Status, Slot,
} from '../templates/index.js';

export interface Mounted<TConfig> {
  /** Re-renders from the new config without losing the controller's own state. */
  update(config: Partial<TConfig>): void;
  /** Removes every listener it added and clears the container. */
  dispose(): void;
}

export interface CodeFieldConfig extends CodeFieldProps {
  onChange?(code: string): void;
}
export interface MountedCodeField extends Mounted<CodeFieldConfig> {
  /** The digits entered so far. */
  readonly value: string;
}
export declare function mountCodeField(container: Element, config?: CodeFieldConfig): MountedCodeField;

export interface SegmentedControlConfig extends SegmentedControlProps {
  defaultValue?: string;
  onChange?(value: string): void;
}
export interface MountedSegmentedControl extends Mounted<SegmentedControlConfig> {
  readonly value: string | undefined;
}
export declare function mountSegmentedControl(
  container: Element, config?: SegmentedControlConfig,
): MountedSegmentedControl;

export interface TextareaConfig extends TextareaProps {
  onChange?(event: Event): void;
}
export interface MountedTextarea extends Mounted<TextareaConfig> {
  readonly value: string;
}
export declare function mountTextarea(container: Element, config?: TextareaConfig): MountedTextarea;

export interface ToastOptions extends ToastProps {
  id?: string;
  /** Milliseconds. `0` never auto-dismisses — the default for an error. */
  duration?: number;
}
export interface ToastViewportConfig {
  placement?: 'top' | 'bottom' | 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';
  duration?: number;
  max?: number;
  iconHref?: string;
}
export interface MountedToasts {
  /** Raises a message. A bare string is its body. Returns the id. */
  toast(options: ToastOptions | string): string;
  dismiss(id: string): void;
  dispose(): void;
}
export declare function mountToasts(container: Element, config?: ToastViewportConfig): MountedToasts;

export interface TooltipConfig extends TooltipProps {
  id?: string;
}
export interface MountedTooltip extends Mounted<TooltipConfig> {
  readonly open: boolean;
}
export declare function mountTooltip(container: Element, config?: TooltipConfig): MountedTooltip;

/**
 * Binds tooltip markup that is already in the document — one composed into a
 * bigger render, or one that arrived from the server. Sets the trigger's
 * aria-describedby and wires hover, focus and Escape. Without it, composed
 * tooltip markup is present and inert.
 */
export declare function attachTooltip(wrapper: Element): { readonly open: boolean; dispose(): void };

export type { Status, Slot };
