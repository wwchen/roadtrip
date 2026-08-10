# Vendored LDS core

`lds.css`, `apca-palette.css`, and `fonts/` are copied verbatim from
[`matthewlew/lds`](https://github.com/matthewlew/lds) `dist/` at commit
`214e442ba843a7e8c9c86d5b604016b79e8f2170`.

LDS is not published to a package registry roadtrip can install from, and
roadtrip has no build/bundle step (its HTML entry points are `<link>`ed
directly), so the built output is vendored rather than referenced live.

The theme that paints these components — `roadtrip-zion.css` — lives one
directory up, in this repo, not in LDS. It targets the same role variables
(`--c-*`, `--gray-*`, `--surface-*`, `--th-*`, etc.) that `lds.css` reads.

## Refreshing

1. In `matthewlew/lds`: `npm run build`
2. Copy `dist/lds.css` → `vendor/lds/lds.css`
3. Copy `dist/apca-palette.css` → `vendor/lds/apca-palette.css`
4. Copy `dist/fonts/*` → `vendor/lds/fonts/`
5. Update the commit hash above
6. Diff `roadtrip-zion.css` against the new `lds.css` role list for any
   added/renamed variables before shipping
