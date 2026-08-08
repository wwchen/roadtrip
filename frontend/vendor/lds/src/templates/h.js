import { attrs } from './attrs.js';
import { raw, slot } from './escape.js';

// A markup builder for composing trees of templates.
//
// The templates take slots, and a slot takes `raw()` markup — which means
// anything nested needs building before it can be passed in. Doing that by hand
// is string concatenation with escaping decisions at every join, which is where
// injection bugs come from. This does it structurally instead:
//
//   h('div', { className: 'stack' },
//     h(banner, { status: 'error', title: 'Failed' }, 'Retry.'),
//     h(button, { variant: 'primary' }, 'Review'))
//
// It is not a virtual DOM and there is no reconciliation — it returns `raw()`
// markup, immediately. State belongs to a controller.
//
// Children are ESCAPED unless they are `raw()` or came from another h() call, so
// text stays text without anyone remembering to escape it.
const VOID = new Set([
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr',
]);

export function h(type, props, ...children) {
  const kids = children.flat(Infinity).filter((v) => v !== null && v !== undefined && v !== false);
  const inner = kids.map(slot).join('');

  // A template (or any function) receives its children as one raw slot, which is
  // the shape every LDS template already takes.
  if (typeof type === 'function') {
    const { key, ...rest } = props || {};
    void key; // React's list key has no meaning here
    const out = type(kids.length ? { ...rest, children: raw(inner) } : { ...rest });
    // Templates return HTML strings; a component built out of h() returns raw().
    return raw(typeof out === 'string' ? out : slot(out));
  }

  const { key, children: _ignored, ...rest } = props || {};
  void key; void _ignored;
  const open = `<${type}${attrs(rest, type)}`;
  return raw(VOID.has(type) ? `${open}/>` : `${open}>${inner}</${type}>`);
}

/** Renders a tree into an element. The vanilla equivalent of a render call. */
export function mount(container, tree) {
  container.innerHTML = slot(tree);
  return container;
}
