import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';

const read = (path: string) => readFileSync(join(process.cwd(), path), 'utf8');

const TOKENS = read('src/tokens/tokens.css');
const SHEET_CSS = read('src/ui/sheet.css');
const PLACE_SHEET_CSS = read('src/features/place/place-sheet.css');

const PLACE_SHEET_LAYER = '--rt-z-place-sheet';
const MAP_SHELL_LAYERS = [
  '--rt-z-map',
  '--rt-z-map-panel',
  '--rt-z-map-control',
  '--rt-z-map-overlay',
  '--rt-z-map-account',
  '--rt-z-sheet-scrim',
  '--rt-z-sheet',
];
const LAYERS_ABOVE_THE_MAP = ['--rt-z-modal', '--rt-z-topbar', '--rt-z-chrome'];

function layer(name: string): number {
  const match = new RegExp(`${name}:\\s*(\\d+)`).exec(TOKENS);
  if (match === null) throw new Error(`${name} is not on the stacking scale in tokens.css`);
  return Number(match[1]);
}

const ruleOf = (css: string, selector: string): string =>
  new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';

describe('the place sheet on the stacking scale', () => {
  test('the sheet takes a depth from the scale rather than leaving it at auto', () => {
    expect(ruleOf(SHEET_CSS, '.rt-sheet')).toContain(`z-index: var(${PLACE_SHEET_LAYER})`);
  });

  test('the chrome that stands in for the sheet takes the same depth', () => {
    expect(ruleOf(PLACE_SHEET_CSS, '.rt-place-chrome')).toContain(`z-index: var(${PLACE_SHEET_LAYER})`);
  });

  test('it clears every overlay the map shell draws beside it', () => {
    for (const name of MAP_SHELL_LAYERS) expect(layer(PLACE_SHEET_LAYER)).toBeGreaterThan(layer(name));
  });

  test('and stays under the topbar it must not cover', () => {
    for (const name of LAYERS_ABOVE_THE_MAP) expect(layer(PLACE_SHEET_LAYER)).toBeLessThan(layer(name));
  });
});
