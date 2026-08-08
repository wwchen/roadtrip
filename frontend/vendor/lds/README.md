# @lew/lds

The Lew Design System as plain HTML over a one-token CSS architecture:
primitives → semantic emphasis roles → components → themes.

Components carry no styles of their own. They emit LDS class names and the paint
comes entirely from the stylesheet — which is what lets a theme repaint the whole
system without a single component branching on the theme's name.

## Install

```bash
npm install @lew/lds
```

No framework, no peer dependencies, no build step.

A component is a function that returns a string of HTML:

```js
import { button, textField, h, mount } from '@lew/lds';
import '@lew/lds/css';

mount(document.getElementById('form'),
  h('div', { className: 'stack' },
    h(textField, { label: 'Work email', required: true }),
    h(button, { variant: 'primary', iconStart: 'check' }, 'Save changes')));
```

`h()` composes elements and components into a tree and returns `raw()` markup;
`mount()` puts it in the document. Neither is required — every component can be
called directly and its string used however you like, including on a server.

### Slots escape by default

Anything passed into a slot — `children`, `title`, `actions` — is treated as
TEXT and escaped. To compose markup into one, mark it with `raw()`:

```js
banner({ status: 'error', title: 'Failed',
  actions: raw(button({ variant: 'primary', children: 'Retry' })) });
```

The wrapper is the point: the unsafe path has to be chosen, not reached by
forgetting to escape.

### The five that hold state

CodeField, SegmentedControl, Textarea, Toast and Tooltip need behaviour a string
cannot carry, so each ships a controller alongside its template:

```js
import { mountCodeField, mountToasts } from '@lew/lds/controllers';

const code = mountCodeField(el, { length: 6, onChange: (v) => check(v) });
code.update({ verifying: true });
code.dispose();

const toasts = mountToasts(document.body);
toasts.toast({ status: 'success', children: 'Saved.' });
```

Every controller is `mountX(container, config) → { update, dispose }`.
`dispose()` removes every listener it added and clears the container.

For markup already in the document — composed into a larger tree, or rendered on
a server — `attachTooltip(wrapper)` binds behaviour without re-rendering.

### The markup is the specification

`markup-contract.json` pins the exact HTML all 28 components emit across 103
prop combinations, and `npm test` diffs against it. It was generated from the
React implementation this package used to be, and frozen before that
implementation was removed — so the markup is a guarantee that outlived the
binding that produced it, not an accident of the current one.

## Icons

Icons come from [`@lew/open-icons`](../open-icons), a separate package with no
dependency on LDS, so an app can consume the sprite without consuming the design
system. It is installed as a dependency here and every component resolves
against it by default.

If you serve the sprite from your own static directory or a CDN, say so once at
startup:

```js
import { setIconSprite } from '@lew/lds';
setIconSprite('/static/icons.svg');
```

Any component also takes a one-off `iconHref`. Icon props take either a sprite
name (`iconStart: 'check'`) or composed markup.

## Components

| | |
| --- | --- |
| **Buttons** | Button (variants, sizes, prefix/suffix icons, subtitle, icon-only, FAB, `href`), ButtonGroup (orientation, hug vs fill, split cancel, conversion bar, mobile stacking), Link (inline / quiet / standalone) |
| **Forms** | TextField (inset icons, joined dial-code prefix), Textarea, Select, Checkbox, Radio, Toggle, CodeField (one-time code), SegmentedControl |
| **Status** | Banner, Inline, Toast, Tag, Chip |
| **Navigation & data** | Nav (brand bar / app bar), Tabs, Table, Row, Menu |
| **Overlays** | Modal (dialog / bottom sheet / side sheet, stacked flows), Tooltip |
| **States** | Skeleton, EmptyState |
| **Foundations** | Avatar, Icon, Card |

Three things share one status vocabulary — Banner, Inline and Toast all read the
same `status → icon` map, so an inline error and a toast error cannot disagree
about what red means. The blocking statuses (`warning`, `caution`, `error`) take
the filled glyph, because at 16–20px a filled triangle reads as a stop signal
where an outline reads as another outline in a form full of them.

### Toast vs Banner

A banner is a field the page contains and it stays until the condition changes.
A toast is chrome the system raises and it leaves on its own. If dismissing it
can lose information the user needs, it wanted to be a banner. Errors raised as
toasts never auto-dismiss.

### Tooltip

A tooltip is what an icon-only button says when you ask it its name — it opens on
hover **and** on focus, because hover alone means a keyboard user never gets the
label. It must not hold anything interactive: a hover bubble cannot be reached by
a pointer without a hover bridge, so a control inside one is unreachable for some
users. That case wants a Menu or a Modal.

## Theming

Core ships the portfolio brand (oat neutrals, green accent ramp) at `:root` with
no theme file needed. Emphasis classes (`emph-plain/subtle/soft/strong/stark`)
resolve the object colour roles; `hue-*` repoints the brand ramp for status use.

Four themes ship, each a class on `<html>` alongside `mode-light` / `mode-dark`:

| theme | class | import |
| --- | --- | --- |
| Core / Portfolio | *(none)* | ships at `:root` |
| Palette | `theme-palette` | `@lew/lds/css/themes/palette` |
| Product | `theme-product` | `@lew/lds/css/themes/product` |
| Roadtrip | `theme-roadtrip` | `@lew/lds/css/themes/roadtrip` |

Themes only override tokens — `--c-*`, `--gray-*`, `--th-*`, `--text-*`, radii,
shadows, density, icon size. Components never branch on a theme name.

## Fonts

Coconat (display), Ronzino (UI and body) and Martian Mono (meta) self-host from
`@lew/lds/fonts/`, which resolves to `css/fonts/` inside the package.

They live *inside* `css/` on purpose: `lds.css` reaches them with a relative
`url('fonts/…')`, so the stylesheet and its fonts have to travel together. Serve
or copy the `css/` directory whole and the faces resolve with no configuration —
split them apart and every face 404s while the CSS still loads, which shows up as
silently wrong typography rather than an error.

## Adherence lint

`adherence.oxlintrc.json` is generated from this package's own source — component
names from the source tree, prop names and their allowed values from the `.d.ts`,
and the token registry from the CSS. It warns on raw hex colours, raw pixel
values, fonts outside the three the system ships, unknown props, out-of-range
prop values, and imports that reach past the package entry point into component
internals.

```bash
node scripts/build-adherence.mjs   # from the repo root, after changing components or CSS
```

## Tests

From the repo root:

```bash
npm test
```

Diffs every component against the frozen markup contract, checks that every icon name
and CSS class the components reference actually exists, typechecks the published
`.d.ts` surface against real usage, and loads all 32 docs cards in a headless
browser asserting no console errors, no failed requests, and no `<use>` pointing
at a symbol the sprite does not define.
