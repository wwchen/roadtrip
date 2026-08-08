// Controllers — the behaviour the templates cannot carry.
//
// Every one takes the roadtrip shape:
//
//   mountX(container, config) -> { dispose(), update(config) }
//
// so an LDS component drops into a codebase that already mounts its own
// components the same way, with no adapter in between. `dispose()` removes every
// listener it added and clears the container; `update()` re-renders from the new
// config without losing the controller's own state.
export { mountCodeField } from './code-field.js';
export { mountSegmentedControl } from './segmented-control.js';
export { mountTextarea } from './textarea.js';
export { mountToasts } from './toast.js';
export { mountTooltip, attachTooltip } from './tooltip.js';
