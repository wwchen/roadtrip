# @lew/open-icons

174 symbols on a 24px grid with a live area of 20×20 and a stroke of 2, from the
[Open Icons](https://github.com/matthewlew/open-icons) set (MIT).

**This package has no dependency on LDS or on any design system.** Drop in
`icons.svg` and reference symbols directly — no CSS, no components, no
JavaScript:

```html
<svg width="20" height="20"><use href="/icons.svg#search" /></svg>
```

Apps already using LDS can use its `Icon` component instead, which is a thin
wrapper over exactly this.

## Install

```bash
npm install @lew/open-icons
```

## Using it

The sprite is the product. The JS surface exists only so a bundler can resolve
the asset URL and so icon names can be checked at build time.

```js
import { spriteUrl, useHref, hasIcon, ICON_NAMES } from '@lew/open-icons';

useHref('chevron-right');        // → "/assets/icons-a1b2c3.svg#chevron-right"
hasIcon('definitely-not-real');  // → false
ICON_NAMES.length;               // → 174
```

To copy the sprite into your own static directory instead, import the file
path directly:

```js
import sprite from '@lew/open-icons/icons.svg';
```

`icons.json` (`{grid, stroke, icons: {name: innerMarkup}}`) is there for anything
that renders markup at runtime rather than referencing a sprite. `names.json` is
just the names, for a manifest or a lint rule.

## Naming

Names are kebab-case and match upstream — `chevron-right`, `check-circle`,
`more-horizontal`. Many have a `-fill` counterpart.

## The `${u}` placeholder, and why the sprite is generated

54 of the 174 icons carry a mask: a mark that crosses a wall needs a moat cut out
of that wall, and a moat is a mask. A mask needs an id, and `icons.json` leaves
that id as a `${u}` placeholder for the consumer to make unique.

In a sprite the substitution is **per symbol**, not per rendered instance. A
symbol is declared once and `<use>` clones it, and `url(#…)` inside the clone
resolves against the original document — so one unique id per symbol is both
necessary and sufficient, and every `<use>` of `warning-fill` correctly points at
the same mask element.

Getting this wrong is not subtle. A sprite that ships the placeholder literally
collapses 54 masks onto four ids (`k${u}` ×30, `m${u}` ×16, `t${u}` ×8, `b${u}`
×2, plus `cp${u}`), and every reference then resolves to the first match in the
document — so 30 different filled glyphs all render with `add-circle-fill`'s
mask, `warning-fill` and `close-circle-fill` among them.

That is why `icons.svg` is generated and not hand-edited:

```bash
npm run build     # regenerate icons.svg + names.json + src/names.js from icons.json
npm test          # assert: no placeholders, no duplicate ids, no dangling url(#…)
```

`npm test` fails the build on any of the three, so the defect cannot come back.

## The stroke is not a CSS knob

The geometry is weight-aware: every clearance in the set is a ratio of the
stroke, not a constant, so a different weight is a different drawing rather than
the same paths at a different `stroke-width`. Re-export the set for another
weight; do not restyle it downstream.

Concretely: **never apply a rule that sets `stroke-width` across a whole icon.**
The mask strokes are 5–6 units wide on purpose, and overriding them to the
nominal weight closes every moat in the set.

## Licence

MIT, as upstream.
