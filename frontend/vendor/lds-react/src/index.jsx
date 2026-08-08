// @lew/lds-react — React components over @lew/lds.
//
// @lew/lds itself stays framework-free: every template is still `(props) =>
// string`, every controller still `mountX(el, config) -> {dispose, update}`.
// This package doesn't change any of that — it wraps it, so a React app (a
// Claude Design canvas, a Claude Code scaffold, a future React consumer) gets
// real components with real props and real event handlers, while a
// framework-free app (Roadtrip, today) never has to install React at all.
//
//   import { Button, Modal } from '@lew/lds-react';
//   <Button variant="primary" onClick={() => setOpen(true)}>Open</Button>
//   <Modal title="Confirm" onClose={() => setOpen(false)}>…</Modal>
//
// Import the same CSS you already import for @lew/lds — this package carries
// no styles of its own:
//
//   import '@lew/lds/css';
//
// See README.md for the composition and event-handling model this wraps
// (dangerouslySetInnerHTML + delegated events, not a virtual DOM).
export {
  Avatar, Banner, Button, ButtonGroup, Card, Checkbox, Chip, EmptyState,
  Icon, Inline, Link, Menu, Modal, Nav, Radio, Row, Select, Skeleton,
  Table, Tabs, Tag, TextField, Toast, Toggle,
} from './components.jsx';

export {
  CodeField, SegmentedControl, Textarea, Tooltip, ToastProvider, useToast,
} from './controllers.jsx';

export { toSlot } from './runtime.jsx';

export {
  setIconSprite, getIconSprite, resolveSprite, STATUS_ICON,
  hueForName, initialsForName, DIAL_CODES, dialOptions,
} from '@lew/lds';
