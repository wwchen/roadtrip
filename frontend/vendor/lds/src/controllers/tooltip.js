import { tooltip as tooltipTemplate } from '../templates/tooltip.js';

let seq = 0;

// Opens on hover AND on focus. Hover alone means a keyboard user never gets the
// label — and for an icon-only button, the tooltip IS the label.
//
// Two ways in, because a tooltip is rarely alone on the page:
//
//   mountTooltip(el, { label: 'Search', children: button({ … }) })
//     renders the markup into `el` and binds it.
//
//   attachTooltip(wrapper)
//     binds markup that is already in the document — a tooltip composed into a
//     bigger tree, or one that arrived from the server. Without this, composing
//     a tooltip into a larger render would mean the bubble is present and inert.
export function attachTooltip(wrapper) {
  const bubble = wrapper.querySelector('.lds-tooltip__bubble');
  if (!bubble) return { open: false, dispose() {} };

  // aria-describedby, not aria-label: the trigger keeps whatever accessible name
  // it already has, and the tooltip adds to it rather than replacing it.
  if (!bubble.id) bubble.id = `lds-tooltip-${seq++}`;
  const trigger = Array.from(wrapper.children).find((el) => el !== bubble);
  if (trigger) trigger.setAttribute('aria-describedby', bubble.id);

  let open = false;
  const set = (next) => {
    open = next;
    bubble.setAttribute('data-open', open ? 'true' : 'false');
  };
  const show = () => set(true);
  const hide = () => set(false);
  // Escape closes a tooltip that is covering something the user is trying to
  // read, without moving focus off the trigger.
  const onKeyDown = (e) => { if (e.key === 'Escape' && open) hide(); };

  // mouseenter/mouseleave do not bubble, so these bind to the wrapper itself.
  wrapper.addEventListener('mouseenter', show);
  wrapper.addEventListener('mouseleave', hide);
  wrapper.addEventListener('focusin', show);
  wrapper.addEventListener('focusout', hide);
  wrapper.addEventListener('keydown', onKeyDown);

  return {
    get open() { return open; },
    dispose() {
      wrapper.removeEventListener('mouseenter', show);
      wrapper.removeEventListener('mouseleave', hide);
      wrapper.removeEventListener('focusin', show);
      wrapper.removeEventListener('focusout', hide);
      wrapper.removeEventListener('keydown', onKeyDown);
    },
  };
}

export function mountTooltip(container, config = {}) {
  let cfg = { ...config };
  const id = cfg.id || `lds-tooltip-${seq++}`;
  let bound;

  const render = () => {
    container.innerHTML = tooltipTemplate({ ...cfg, id });
    bound = attachTooltip(container.firstElementChild);
  };
  render();

  return {
    get open() { return bound.open; },
    update(next = {}) {
      bound.dispose();
      cfg = { ...cfg, ...next };
      render();
    },
    dispose() {
      bound.dispose();
      container.innerHTML = '';
    },
  };
}
