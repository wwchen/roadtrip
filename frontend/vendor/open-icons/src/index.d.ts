/** Every symbol id in the sprite, sorted. */
export declare const ICON_NAMES: readonly string[];

/** The drawing grid. Icons are authored on 24 with a live area of 20×20. */
export declare const GRID: 24;

/**
 * The stroke the set was exported at. Metadata, not a knob — the geometry is
 * weight-aware, so a different weight is a different drawing. Never restyle
 * `stroke-width` downstream.
 */
export declare const STROKE: 2;

/** The sprite's URL, resolved against this package. */
export declare const spriteUrl: string;

/** Whether the set contains `name`. */
export declare function hasIcon(name: string): boolean;

/** `href` for a `<use>` element, e.g. `useHref('search')`. */
export declare function useHref(name: string, sprite?: string): string;

declare const _default: readonly string[];
export default _default;
