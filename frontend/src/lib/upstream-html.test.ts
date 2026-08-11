import { describe, expect, test } from 'vitest';
import { descriptionHtml, sanitizeUpstreamHtml, upstreamDecorations } from './upstream-html';

describe('sanitizeUpstreamHtml', () => {
  test('empty input yields empty output', () => {
    expect(sanitizeUpstreamHtml('')).toBe('');
    expect(sanitizeUpstreamHtml('   ')).toBe('');
    expect(sanitizeUpstreamHtml(null)).toBe('');
    expect(sanitizeUpstreamHtml(42)).toBe('');
  });

  test('keeps the structural tags providers actually send', () => {
    const html = '<h3>Fees</h3><p>Standard <strong>$25</strong></p><ul><li>Extra vehicle</li></ul>';

    expect(sanitizeUpstreamHtml(html)).toBe(html);
  });

  test('unwraps a disallowed tag and keeps its text', () => {
    expect(sanitizeUpstreamHtml('<span style="color:red">Quiet hours</span>')).toBe('Quiet hours');
    expect(sanitizeUpstreamHtml('<font size="2"><p>Kept</p></font>')).toBe('<p>Kept</p>');
  });

  test('a script tag loses its markup and cannot execute', () => {
    const result = sanitizeUpstreamHtml('<script>alert(1)</script><p>After</p>');

    expect(result).not.toContain('<script');
    expect(result).toContain('<p>After</p>');
  });

  test('strips attributes outside the whitelist', () => {
    const result = sanitizeUpstreamHtml('<p class="x" onclick="alert(1)" style="color:red">Text</p>');

    expect(result).toBe('<p>Text</p>');
  });

  test('keeps href and title on a link, and marks it as leaving the app', () => {
    const result = sanitizeUpstreamHtml('<a href="https://recreation.gov" title="Book">Book</a>');

    expect(result).toContain('href="https://recreation.gov"');
    expect(result).toContain('title="Book"');
    expect(result).toContain('target="_blank"');
    expect(result).toContain('rel="noopener noreferrer"');
  });

  test.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html;base64,PHNjcmlwdD4=',
    'vbscript:msgbox',
  ])('drops the href for a %s link while keeping the text', (href) => {
    const result = sanitizeUpstreamHtml(`<a href="${href}">Click</a>`);

    expect(result).not.toContain('href');
    expect(result).toContain('Click');
  });

  test.each(['https://example.com', 'mailto:a@b.co', 'tel:+15551234', '/local/path', '#anchor'])(
    'allows %s',
    (href) => {
      expect(sanitizeUpstreamHtml(`<a href="${href}">x</a>`)).toContain(`href="${href}"`);
    },
  );

  test('sanitises nested content, not just the top level', () => {
    const result = sanitizeUpstreamHtml('<p><span><img src="x" onerror="alert(1)"></span></p>');

    expect(result).not.toContain('onerror');
    expect(result).not.toContain('<img');
  });
});

describe('descriptionHtml', () => {
  test('passes provider markup through the sanitiser', () => {
    expect(descriptionHtml('<p>Nice <em>spot</em></p>')).toBe('<p>Nice <em>spot</em></p>');
    expect(descriptionHtml('<p onclick="x">Nice</p>')).toBe('<p>Nice</p>');
  });

  test('paragraphs plain text on blank lines', () => {
    expect(descriptionHtml('First para.\n\nSecond para.')).toBe(
      '<p>First para.</p><p>Second para.</p>',
    );
  });

  test('a single newline inside a paragraph is a line break', () => {
    expect(descriptionHtml('Line one\nLine two')).toBe('<p>Line one<br>Line two</p>');
  });

  test('text that looks like markup is sanitised, not escaped', () => {
    const result = descriptionHtml('Watch out for <script>alert(1)</script> here');

    expect(result).not.toContain('<script');
    expect(result).toContain('Watch out for');
    expect(result).toContain('here');
  });

  test('escapes special characters on the plain-text path', () => {
    expect(descriptionHtml('Sites 1 & 2 cost less than $30')).toBe(
      '<p>Sites 1 &amp; 2 cost less than $30</p>',
    );
  });

  test('nothing in, nothing out', () => {
    expect(descriptionHtml('')).toBe('');
    expect(descriptionHtml(undefined)).toBe('');
  });
});

describe('upstreamDecorations', () => {
  test('a missing record decorates nothing', () => {
    expect(upstreamDecorations(null)).toEqual({
      parentName: null,
      feesHtml: '',
      stayLimit: '',
      directionsHtml: '',
    });
  });

  test('pulls the RIDB fields the drawer shows', () => {
    const result = upstreamDecorations({
      RECAREA: [{ RecAreaName: ' Buffalo National River ' }, { RecAreaName: 'Ignored' }],
      FacilityUseFeeDescription: '<p>$25/night</p>',
      StayLimit: ' 14 days ',
      FacilityDirections: '<p>Off Highway 7</p>',
    });

    expect(result).toEqual({
      parentName: 'Buffalo National River',
      feesHtml: '<p>$25/night</p>',
      stayLimit: '14 days',
      directionsHtml: '<p>Off Highway 7</p>',
    });
  });

  test('the parent is the first rec area, and absent when there is none', () => {
    expect(upstreamDecorations({ RECAREA: [] }).parentName).toBeNull();
    expect(upstreamDecorations({ RECAREA: [{ RecAreaName: '  ' }] }).parentName).toBeNull();
    expect(upstreamDecorations({}).parentName).toBeNull();
  });

  test('sanitises the markup it returns', () => {
    const result = upstreamDecorations({
      FacilityUseFeeDescription: '<p onclick="x">Fees</p><script>alert(1)</script>',
    });

    expect(result.feesHtml).toBe('<p>Fees</p>alert(1)');
  });
});
