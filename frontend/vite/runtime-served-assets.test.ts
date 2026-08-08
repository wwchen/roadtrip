// The sandbox chrome is easy to lose and its absence is silent: a page missing the
// user switcher looks merely signed-out in a sandbox, which is indistinguishable
// from a real auth problem. The migrated watches page shipped without it once
// already. These tests pin the tag set so the next strangler phase cannot repeat
// that by omission.
import { describe, expect, test } from 'vitest';
import type { HtmlTagDescriptor, IndexHtmlTransformHook } from 'vite';
import { runtimeServedAssets } from './runtime-served-assets';

/** The tags the plugin injects, via the same hook Vite calls. */
function inject(): HtmlTagDescriptor[] {
  const plugin = runtimeServedAssets();
  const transform = plugin.transformIndexHtml;
  if (!transform || typeof transform === 'function' || !transform.handler) {
    throw new Error('expected an object form transformIndexHtml with a handler');
  }
  const handler = transform.handler as IndexHtmlTransformHook;
  const result = handler.call({} as never, '', {} as never);
  if (!Array.isArray(result)) throw new Error('expected the handler to return tags');
  return result as HtmlTagDescriptor[];
}

const hrefs = (tags: HtmlTagDescriptor[]): string[] =>
  tags.filter((t) => t.tag === 'link').map((t) => String(t.attrs?.href));

const srcs = (tags: HtmlTagDescriptor[]): string[] =>
  tags.filter((t) => t.tag === 'script').map((t) => String(t.attrs?.src));

describe('runtimeServedAssets', () => {
  test('injects the token bridge and both sandbox stylesheets', () => {
    expect(hrefs(inject())).toEqual([
      '/web/design-system/tokens.css',
      '/web/sandbox-banner.css',
      '/web/sandbox-user-switcher.css',
    ]);
  });

  // Both files use `export`, so a classic script would be a syntax error.
  test('injects both sandbox modules as module scripts', () => {
    const tags = inject();
    expect(srcs(tags)).toEqual(['/web/sandbox-banner.js', '/web/sandbox-user-switcher.js']);
    for (const tag of tags.filter((t) => t.tag === 'script')) {
      expect(tag.attrs?.type).toBe('module');
    }
  });

  test('stylesheets go to the head and scripts to the end of the body', () => {
    for (const tag of inject()) {
      expect(tag.injectTo).toBe(tag.tag === 'link' ? 'head' : 'body');
    }
  });

  // `post` is what keeps Vite from trying to resolve and bundle these; a module
  // script pointing outside the Vite root fails the build outright.
  test('runs after Vite has transformed the HTML', () => {
    const transform = runtimeServedAssets().transformIndexHtml;
    expect(typeof transform === 'object' && transform.order).toBe('post');
  });
});
