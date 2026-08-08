// One status vocabulary for the whole system.
//
// Banner, Inline and Toast all say the same five things, so they read from one
// map rather than three copies — the failure mode of copies is that an inline
// error and a banner error quietly stop agreeing about what red means.
//
// The blocking statuses take the FILLED glyph: at 16–20px a filled triangle or
// disc reads as a stop signal at a glance, where the line version reads as
// another outline in a form full of outlines. Success and info stay line — they
// are confirmations, and a filled tick shouts louder than the news deserves.
//
// Colour alone is not an accessible carrier, which is why the icon is shown by
// default rather than on request.
export const STATUS_ICON = Object.freeze({
  info: 'info',
  success: 'check-circle',
  warning: 'warning-fill',
  caution: 'warning-fill',
  error: 'close-circle-fill',
});

export const STATUSES = Object.freeze(Object.keys(STATUS_ICON));
