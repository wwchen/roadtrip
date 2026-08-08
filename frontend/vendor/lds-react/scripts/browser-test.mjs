// Drives the React wrappers in a real browser — the half of this package
// smoke-test.entry.jsx can't reach, since useControllerMount's mount/update
// lifecycle and delegated DOM handlers only exist once React has actually
// committed to a document. Mirrors the vanilla repo's own scripts/dom-test.mjs.
import { chromium } from 'playwright';
import esbuild from 'esbuild';
import { createServer } from 'node:http';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const work = await mkdtemp(join(tmpdir(), 'lds-react-browser-test-'));

await esbuild.build({
  entryPoints: [join(HERE, 'browser-test.entry.jsx')],
  bundle: true,
  format: 'iife',
  outfile: join(work, 'bundle.js'),
  logLevel: 'warning',
});
await writeFile(join(work, 'index.html'),
  '<!doctype html><html><body><div id="root"></div><script src="/bundle.js"></script></body></html>');

const server = createServer(async (req, res) => {
  const path = req.url.split('?')[0];
  if (path === '/bundle.js') {
    res.writeHead(200, { 'content-type': 'text/javascript' });
    res.end(await (await import('node:fs/promises')).readFile(join(work, 'bundle.js')));
    return;
  }
  res.writeHead(200, { 'content-type': 'text/html' });
  res.end(await (await import('node:fs/promises')).readFile(join(work, 'index.html')));
});
await new Promise((r) => server.listen(0, r));
const base = `http://127.0.0.1:${server.address().port}`;

const { existsSync } = await import('node:fs');
const SANDBOX_CHROMIUM = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';
const executablePath = process.env.CHROMIUM_PATH
  || (existsSync(SANDBOX_CHROMIUM) ? SANDBOX_CHROMIUM : undefined);
const browser = await chromium.launch(executablePath ? { executablePath } : {});

const fails = [];
const check = (ok, what) => { if (!ok) fails.push(what); };

const page = await browser.newPage();
page.on('pageerror', (e) => fails.push(`pageerror: ${e.message}`));
page.on('console', (msg) => { if (msg.type() === 'error') fails.push(`console.error: ${msg.text()}`); });
await page.goto(base, { waitUntil: 'networkidle' });

const text = async (sel) => (await page.locator(sel).textContent())?.trim();

// ---- Banner: delegated onDismiss reaches a real React state update ----------
await page.click('#banner .lds-banner__dismiss');
check(await text('#out-banner-dismissed') === 'true', 'Banner onDismiss did not update React state');
check(await page.locator('#banner .lds-banner').count() === 0, 'Banner did not unmount after dismissal');

// ---- Chip: onRemove fires, onClick does NOT also fire (first-match delegation) --
await page.click('#chip .lds-chip__remove');
check(await text('#out-chip-removed') === 'true', 'Chip onRemove did not update React state');
check(await text('#out-chip-clicked') === 'false', 'Chip onClick incorrectly fired alongside onRemove');

// ---- Modal: onClose and onBack are two distinct delegated handlers ----------
await page.click('#modal .lds-modal__back');
check(await text('#out-modal-back') === 'true', 'Modal onBack did not update React state');
check(await text('#out-modal-closed') === 'false', 'Modal onClose incorrectly fired for a back click');
await page.click('#modal .lds-modal__close');
check(await text('#out-modal-closed') === 'true', 'Modal onClose did not update React state');

// ---- CodeField: real focus movement + onChange(code) from useControllerMount --
await page.locator('#codefield .lds-field__code input').first().focus();
await page.keyboard.type('12');
check(await text('#out-code') === '12', `CodeField onChange did not deliver typed digits, got ${await text('#out-code')}`);
const focusedIndex = await page.evaluate(() => {
  const inputs = Array.from(document.querySelectorAll('#codefield .lds-field__code input'));
  return inputs.indexOf(document.activeElement);
});
check(focusedIndex === 2, `CodeField focus did not walk forward, landed on index ${focusedIndex}`);

// ---- SegmentedControl: click updates React state via onChange(value) --------
await page.click('#segctrl .lds-seg__option:nth-child(2) input');
check(await text('#out-segment') === 'b', `SegmentedControl onChange did not fire, got ${await text('#out-segment')}`);

// ---- Textarea: typing updates state; an UNRELATED re-render preserves focus --
await page.click('#textarea textarea');
await page.keyboard.type('hi');
check(await text('#out-textarea') === 'hi', `Textarea onChange did not deliver typed text, got ${await text('#out-textarea')}`);
await page.evaluate(() => window.__bumpTick());
check(await text('#out-tick') === '1', 'unrelated state bump did not re-render the app');
const stillFocused = await page.evaluate(() =>
  document.activeElement === document.querySelector('#textarea textarea'));
check(stillFocused, 'Textarea lost focus on an unrelated parent re-render (update() should patch, not remount)');

// ---- Tooltip: opens on hover, via the vanilla controller underneath ---------
await page.hover('#tooltip-trigger');
const tooltipOpen = await page.evaluate(() =>
  document.querySelector('#tooltip .lds-tooltip__bubble')?.getAttribute('data-open'));
check(tooltipOpen === 'true', `Tooltip did not open on hover, data-open=${tooltipOpen}`);

// ---- ToastProvider / useToast: raising and auto-dismissing ------------------
await page.click('#raise-toast');
check(await page.locator('.lds-toast').count() === 1, 'useToast().toast() did not raise a toast');
await page.click('.lds-toast .lds-toast__dismiss');
check(await page.locator('.lds-toast').count() === 0, 'Toast dismiss button did not remove the toast');

// ---- Nested composition: a wrapped Button passed as another wrapped ---------
// component's `children` is portaled in, not flattened to markup — so it
// both renders for real and keeps its own onClick (see runtime.jsx's
// useSlotResolution/useSlotPortals doc comments for why this can't be done
// with a plain renderToStaticMarkup flatten).
check(await text('#nested .lds-btn') === 'Nested', 'nested Button did not render its real text inside the Chip (portal did not project)');
await page.click('#nested-button');
check(await text('#out-nested-clicked') === 'true', "nested Button's own onClick did not fire — composition should preserve it, not just its markup");

// ---- List-shaped slot fields: a JSX value renders for real, no [object Object] --
check((await text('#menu-jsx-icon')).includes('Rename') && !(await text('#menu-jsx-icon')).includes('[object Object]'),
  'Menu with a JSX icon field did not render cleanly');
check(await page.locator('#menu-jsx-icon .probe-icon').count() === 1, 'Menu item icon did not actually mount');
check((await text('#table-jsx-cell')).includes('Active') && !(await text('#table-jsx-cell')).includes('[object Object]'),
  'Table with a JSX cell value did not render cleanly');
check(await page.locator('#table-jsx-cell .lds-tag').count() === 1, "Table cell's <Tag> did not actually mount");

// ---- Ref forwarding: resolves to the real element, not the wrapper div -----
const refTag = await page.evaluate(() => window.__refProbeTagName());
check(refTag === 'BUTTON', `ref did not resolve to the real <button>, got <${refTag}>`);

await browser.close();
server.close();
await rm(work, { recursive: true, force: true });

if (fails.length) {
  console.error(`browser-test: ${fails.length} failure(s)\n${fails.map((f) => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log('browser-test: all checks passed');
