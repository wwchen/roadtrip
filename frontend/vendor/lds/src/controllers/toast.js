import { toast as toastTemplate } from '../templates/toast.js';

// Owns the queue and the viewport. Mount once, near the root — a toast is global
// chrome, and two viewports would give you two stacks racing for the same corner.
//
//   const toasts = mountToasts(document.body)
//   toasts.toast({ status: 'success', children: 'Saved.' })
//   toasts.toast({ status: 'error', title: 'Failed', children: 'Retry.' })  // stays
let seq = 0;

export function mountToasts(container, {
  placement = 'bottom', duration = 5000, max = 3, iconHref,
} = {}) {
  const viewport = document.createElement('div');
  viewport.className = `lds-toast-viewport lds-toast-viewport--${placement}`;
  container.appendChild(viewport);

  const timers = new Map();

  const clearTimer = (id) => {
    const t = timers.get(id);
    if (t) { clearTimeout(t); timers.delete(id); }
  };

  const nodeFor = (id) => Array.from(viewport.children).find((n) => n.dataset.toastId === id);

  const dismiss = (id) => {
    const node = nodeFor(id);
    if (node) node.remove();
    clearTimer(id);
  };

  const toast = (opts) => {
    const options = typeof opts === 'string' ? { children: opts } : (opts || {});
    const id = options.id ?? `lds-toast-${seq++}`;
    dismiss(id); // re-raising an id replaces the message rather than stacking it

    const holder = document.createElement('div');
    holder.innerHTML = toastTemplate({ iconHref, ...options });
    const node = holder.firstElementChild;
    node.dataset.toastId = id;
    viewport.appendChild(node);

    // Oldest out first: the newest message is the one the user is waiting for.
    while (viewport.children.length > max) {
      const oldest = viewport.firstElementChild;
      clearTimer(oldest.dataset.toastId);
      oldest.remove();
    }

    // An error stays until it is dismissed. A message you have to catch inside
    // five seconds is a message some people will never read.
    const ms = options.duration ?? (options.status === 'error' ? 0 : duration);
    if (ms > 0) timers.set(id, setTimeout(() => dismiss(id), ms));
    return id;
  };

  const onClick = (e) => {
    const btn = e.target.closest && e.target.closest('.lds-toast__dismiss');
    if (!btn) return;
    const node = btn.closest('.lds-toast');
    if (node) dismiss(node.dataset.toastId);
  };

  viewport.addEventListener('click', onClick);

  return {
    toast,
    dismiss,
    dispose() {
      // A pending timer that fires after teardown would reach for a node that is
      // no longer in the document.
      timers.forEach(clearTimeout);
      timers.clear();
      viewport.removeEventListener('click', onClick);
      viewport.remove();
    },
  };
}
