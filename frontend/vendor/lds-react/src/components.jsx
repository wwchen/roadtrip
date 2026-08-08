// The 23 stateless components, wrapped from their vanilla templates.
//
// Config here is the entire adaptation: which props are `Slot`s (so a React
// node passed there gets flattened to markup before the template sees it),
// and which documented handler props (Modal's `onClose`, Banner's
// `onDismiss`, …) need a real DOM listener — the vanilla template accepts
// and ignores them (see e.g. templates/modal.js: "accepted and ignored: the
// controller binds the handler by delegation after mounting"); this is that
// delegation, written once in runtime.jsx and applied per component here.
import {
  avatar, banner, button, buttonGroup, card, checkbox, chip, emptyState,
  icon, inline, link, menu, modal, nav, radio, row, select, skeleton,
  table, tabs, tag, textField, toast, toggle,
} from '@lew/lds';
import { makeTemplateComponent } from './runtime.jsx';

export const Avatar = makeTemplateComponent('Avatar', avatar);

export const Banner = makeTemplateComponent('Banner', banner, {
  slotKeys: ['title', 'children', 'actions', 'icon'],
  handlers: [{ name: 'onDismiss', event: 'click', selector: '.lds-banner__dismiss' }],
});

export const Button = makeTemplateComponent('Button', button, {
  slotKeys: ['iconStart', 'iconEnd', 'subtitle', 'children'],
  handlers: [{ name: 'onClick', event: 'click', selector: null }],
});

export const ButtonGroup = makeTemplateComponent('ButtonGroup', buttonGroup, {
  slotKeys: ['children', 'detail', 'detailNote'],
});

export const Card = makeTemplateComponent('Card', card, {
  slotKeys: ['kicker', 'title', 'body', 'meta', 'actions', 'children'],
  handlers: [{ name: 'onClick', event: 'click', selector: null }],
});

export const Checkbox = makeTemplateComponent('Checkbox', checkbox, {
  slotKeys: ['label'],
  withChange: true,
});

export const Chip = makeTemplateComponent('Chip', chip, {
  slotKeys: ['children', 'icon', 'caret'],
  handlers: [
    { name: 'onRemove', event: 'click', selector: '.lds-chip__remove' },
    { name: 'onClick', event: 'click', selector: null },
  ],
});

export const EmptyState = makeTemplateComponent('EmptyState', emptyState, {
  slotKeys: ['icon', 'image', 'title', 'body', 'actions'],
});

export const Icon = makeTemplateComponent('Icon', icon);

export const Inline = makeTemplateComponent('Inline', inline, {
  slotKeys: ['icon', 'children'],
});

export const Link = makeTemplateComponent('Link', link, {
  slotKeys: ['children', 'iconEnd'],
  handlers: [{ name: 'onClick', event: 'click', selector: null }],
});

export const Menu = makeTemplateComponent('Menu', menu, {
  listSlotKeys: { items: ['label', 'icon', 'hint'] },
});

export const Modal = makeTemplateComponent('Modal', modal, {
  slotKeys: ['title', 'children', 'actions', 'cancel'],
  handlers: [
    { name: 'onClose', event: 'click', selector: '.lds-modal__close' },
    { name: 'onBack', event: 'click', selector: '.lds-modal__back' },
  ],
});

export const Nav = makeTemplateComponent('Nav', nav, {
  slotKeys: ['logo', 'links', 'title', 'subtitle', 'actions', 'children'],
  handlers: [{ name: 'onBack', event: 'click', selector: '.lds-nav__back' }],
});

export const Radio = makeTemplateComponent('Radio', radio, {
  slotKeys: ['label'],
  withChange: true,
});

export const Row = makeTemplateComponent('Row', row, {
  slotKeys: ['lead', 'title', 'subtitle', 'trail', 'chevron'],
  handlers: [{ name: 'onClick', event: 'click', selector: null }],
});

// A group entry's own `label` is a plain string in the vanilla type (not a
// Slot) — harmless to include here, since a non-JSX value just passes
// through untouched. What this does NOT reach is an option nested inside a
// group's own `options[]` — one level deeper than useListSlotResolution
// walks. Not fixed: no current usage needs it, and it would cost a second,
// conditional list-resolution pass for a shape (grouped AND JSX-labelled)
// that hasn't come up.
export const Select = makeTemplateComponent('Select', select, {
  slotKeys: ['label', 'help', 'error'],
  listSlotKeys: { options: ['label'] },
  withChange: true,
});

export const Skeleton = makeTemplateComponent('Skeleton', skeleton);

// `rows[]` field keys aren't fixed (caller-defined columns), so every field
// on every row is checked — `null` means that to `useListSlotResolution`.
export const Table = makeTemplateComponent('Table', table, {
  listSlotKeys: { columns: ['label'], rows: null },
});

export const Tabs = makeTemplateComponent('Tabs', tabs, {
  listSlotKeys: { tabs: ['label', 'section'] },
});

export const Tag = makeTemplateComponent('Tag', tag, {
  slotKeys: ['children', 'icon'],
});

export const TextField = makeTemplateComponent('TextField', textField, {
  slotKeys: ['label', 'help', 'error', 'iconStart', 'iconEnd', 'prefix'],
  handlers: [{ name: 'endAction.onClick', event: 'click', selector: '.lds-field__adorn--action' }],
  withChange: true,
});

// The presentational half of Toast — renders one message in place, for a
// static composition (a design mockup, a "what does an error toast look
// like" card). The real, interactive, queue-managed usage is
// `<ToastProvider>` + `useToast()` in controllers.jsx; this is not that.
export const Toast = makeTemplateComponent('Toast', toast, {
  slotKeys: ['title', 'children', 'actions', 'icon'],
  handlers: [{ name: 'onDismiss', event: 'click', selector: '.lds-toast__dismiss' }],
});

export const Toggle = makeTemplateComponent('Toggle', toggle, {
  slotKeys: ['label', 'help'],
  withChange: true,
});
