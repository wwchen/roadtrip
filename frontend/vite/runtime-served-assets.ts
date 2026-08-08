import type { HtmlTagDescriptor, Plugin } from 'vite';

/**
 * Site chrome that Ktor serves from the legacy `web/` tree at runtime.
 *
 * These files are deliberately NOT bundled. `tokens.css` is the single source of
 * truth for `--rt-*` color and is served so a token change takes effect without
 * a frontend rebuild; the two sandbox modules are environment-gated no-ops
 * outside a sandbox and are shared verbatim with the still-vanilla pages.
 *
 * Every page of the site carries all of these — see `index.html` and
 * `availability.html` in the repo root, which the migrated entries replace one
 * phase at a time. The user switcher in particular is load-bearing for review:
 * an auth-disabled sandbox 401s every API call until an `rt_session=sandbox:<id>`
 * cookie is picked, and the switcher is the only page-local way to pick one.
 * Without it a migrated page shows nothing but its signed-out state.
 */
const STYLESHEETS = [
  '/web/design-system/tokens.css',
  '/web/sandbox-banner.css',
  '/web/sandbox-user-switcher.css',
];

/**
 * Loaded as modules because they are ES modules — both use `export`, so a
 * classic `<script>` would be a syntax error.
 */
const MODULES = ['/web/sandbox-banner.js', '/web/sandbox-user-switcher.js'];

const tags: HtmlTagDescriptor[] = [
  // Stylesheets in the head, where the legacy pages appended them at the end of
  // <body> — same rules, without painting once unstyled first.
  //
  // Safe to move because neither file can be affected by cascade position:
  // `tokens.css` declares nothing but `:root` custom properties (and `var()`
  // resolves at use time, not parse time, so it does not matter that the bundled
  // LDS CSS referencing `--rt-*` now precedes it), and the two sandbox
  // stylesheets only ever select their own `.sandbox-*` classes. Nothing in the
  // vendored LDS declares an `--rt-*` property, so there is no collision to lose.
  ...STYLESHEETS.map((href) => ({
    tag: 'link',
    attrs: { rel: 'stylesheet', href },
    injectTo: 'head' as const,
  })),
  ...MODULES.map((src) => ({
    tag: 'script',
    attrs: { type: 'module', src },
    injectTo: 'body' as const,
  })),
];

/**
 * Injects the runtime-served chrome into every HTML entry.
 *
 * A plugin rather than literal tags in each `*.html` for two reasons.
 *
 * The blocking one: Vite treats `<script type="module" src>` in an entry as a
 * build input and resolves it. `/web/sandbox-banner.js` lives outside the Vite
 * root, so the build dies with `Failed to resolve /web/sandbox-banner.js from
 * watches.html` — the tags cannot be written in the HTML at all. (A `<link>` is
 * only warned about, which is why `tokens.css` used to sit there and emit
 * "doesn't exist at build time, it will remain unchanged to be resolved at
 * runtime" on every build. That warning is gone now too.)
 *
 * The other: three entries needed an identical block, and each future strangler
 * phase would add a fourth, fifth, sixth — a page that silently forgot the
 * switcher would look signed-out in every sandbox and nothing would catch it.
 */
export function runtimeServedAssets(): Plugin {
  return {
    name: 'roadtrip:runtime-served-assets',
    transformIndexHtml: {
      // `post` is required, not stylistic: Vite's own HTML transform is what
      // resolves and bundles the tags it finds, so injecting after it has run is
      // what leaves these references intact for Ktor to serve.
      order: 'post',
      handler: () => tags,
    },
  };
}
