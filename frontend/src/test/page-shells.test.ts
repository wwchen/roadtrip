// The boot script is duplicated across every shell because it must run
// before first paint. This test is what stops the copies drifting.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { DARK_MODE_CLASS, THEME_COLORS, THEME_STORAGE_KEY } from '@/lib/theme';

const SHELLS = ['index.html', 'watches.html', 'availability.html', 'poi.html'];
const shell = (name: string) => readFileSync(join(process.cwd(), name), 'utf8');

describe.each(SHELLS)('%s', (name) => {
  const html = shell(name);

  test('carries the theme boot script', () => {
    expect(html).toContain(THEME_STORAGE_KEY);
    expect(html).toContain(DARK_MODE_CLASS);
  });

  test('falls back to the OS preference', () => {
    expect(html).toContain('prefers-color-scheme: dark');
  });

  test('runs before the module entry', () => {
    expect(html.indexOf(THEME_STORAGE_KEY)).toBeLessThan(html.indexOf('type="module"'));
  });

  test('still declares the theme class on <html>', () => {
    expect(html).toContain('class="theme-roadtrip-zion"');
  });

  test('has a theme-color meta for the script to update', () => {
    expect(html).toContain('name="theme-color"');
  });

  // The one value the script can't import, so it can drift silently.
  test('pins the dark theme-color to THEME_COLORS', () => {
    expect(html).toContain(THEME_COLORS.dark);
  });
});

test('every shell carries a byte-identical boot script', () => {
  const scripts = SHELLS.map((name) => {
    const match = shell(name).match(/<script>[\s\S]*?<\/script>/);
    expect(match, `${name} has no inline <script>`).not.toBeNull();
    return match![0];
  });
  expect(new Set(scripts).size).toBe(1);
});
