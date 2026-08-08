// @lew/lds — the Lew Design System, as plain HTML.
//
// A component is a function that returns a string of markup:
//
//   import { banner, button, h, mount } from '@lew/lds';
//   mount(el, h(banner, { status: 'error', title: 'Failed' }, 'Retry.'));
//
// No framework, no build step, no runtime. The five components that hold state
// come with a controller alongside their template, in the shape a vanilla
// codebase already mounts things:
//
//   const tip = mountTooltip(el, { label: 'Search', children: button({ … }) });
//   tip.dispose();
//
// Components carry no styles of their own. They emit LDS class names, and the
// paint comes entirely from the stylesheet — which is what lets a theme repaint
// the system without a component branching on the theme's name. Import the CSS
// once at your app's entry point:
//
//   import '@lew/lds/css';
//
// The sprite resolves through @lew/open-icons by default; call setIconSprite if
// you serve it from somewhere else.
//
// Every component's markup is pinned by markup-contract.json — 103 cases,
// diffed on every test run — so the HTML is a specification rather than an
// implementation detail.

export { setIconSprite, getIconSprite, resolveSprite } from './icon-sprite.js';
export { STATUS_ICON } from './status-icons.js';
export { hueForName, initialsForName } from './avatar-name.js';
export { DIAL_CODES, dialOptions } from './dial-codes.js';

// Composition: escaping, the markup builder, and the class-name joiner.
export { escapeHtml, raw, slot } from './templates/escape.js';
export { h, mount } from './templates/h.js';
export { attrs, cx, styleAttr } from './templates/attrs.js';
export { spriteSvg } from './templates/sprite.js';

// Components.
export { avatar } from './templates/avatar.js';
export { banner } from './templates/banner.js';
export { button } from './templates/button.js';
export { buttonGroup } from './templates/button-group.js';
export { card } from './templates/card.js';
export { checkbox } from './templates/checkbox.js';
export { chip } from './templates/chip.js';
export { codeField } from './templates/code-field.js';
export { emptyState } from './templates/empty-state.js';
export { icon } from './templates/icon.js';
export { inline } from './templates/inline.js';
export { link } from './templates/link.js';
export { menu } from './templates/menu.js';
export { modal } from './templates/modal.js';
export { nav } from './templates/nav.js';
export { radio } from './templates/radio.js';
export { row } from './templates/row.js';
export { segmentedControl } from './templates/segmented-control.js';
export { select } from './templates/select.js';
export { skeleton } from './templates/skeleton.js';
export { table } from './templates/table.js';
export { tabs } from './templates/tabs.js';
export { tag } from './templates/tag.js';
export { textField } from './templates/text-field.js';
export { textarea } from './templates/textarea.js';
export { toast } from './templates/toast.js';
export { toggle } from './templates/toggle.js';
export { tooltip } from './templates/tooltip.js';

// Controllers — the behaviour a template cannot carry.
export { mountCodeField } from './controllers/code-field.js';
export { mountSegmentedControl } from './controllers/segmented-control.js';
export { mountTextarea } from './controllers/textarea.js';
export { mountToasts } from './controllers/toast.js';
export { mountTooltip, attachTooltip } from './controllers/tooltip.js';
