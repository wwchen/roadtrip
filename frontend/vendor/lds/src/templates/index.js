// Framework-free bindings: pure functions returning HTML strings.
//
// Same class names, same status map, same sprite resolver the React components
// used — and `npm test` diffs every one of them against a frozen markup
// contract, so an app with no build step gets the same component rather than a
// lookalike.
//
// Naming is lower-camel of the component: Banner → banner, TextField →
// textField. The contract test resolves them that way, so a template that is
// misnamed reports as missing rather than quietly going unchecked.
export { escapeHtml, raw, slot } from './escape.js';
export { h, mount } from './h.js';
export { attrs, cx, styleAttr } from './attrs.js';
export { spriteSvg } from './sprite.js';

export { avatar } from './avatar.js';
export { banner } from './banner.js';
export { button } from './button.js';
export { buttonGroup } from './button-group.js';
export { card } from './card.js';
export { checkbox } from './checkbox.js';
export { chip } from './chip.js';
export { emptyState } from './empty-state.js';
export { icon } from './icon.js';
export { inline } from './inline.js';
export { link } from './link.js';
export { menu } from './menu.js';
export { modal } from './modal.js';
export { nav } from './nav.js';
export { radio } from './radio.js';
export { row } from './row.js';
export { select } from './select.js';
export { skeleton } from './skeleton.js';
export { table } from './table.js';
export { tabs } from './tabs.js';
export { tag } from './tag.js';
export { textField } from './text-field.js';
export { toggle } from './toggle.js';

// The five stateful components. The template is the first paint; the behaviour
// lives in a controller alongside it — see ../controllers/.
export { codeField } from './code-field.js';
export { segmentedControl } from './segmented-control.js';
export { textarea } from './textarea.js';
export { toast } from './toast.js';
export { tooltip } from './tooltip.js';
