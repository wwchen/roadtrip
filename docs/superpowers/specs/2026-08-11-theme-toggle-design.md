# Light / dark theme toggle

**Date:** 2026-08-11
**Status:** Design approved, pending implementation
**Issue:** [#625](https://github.com/wwchen/roadtrip/issues/625)

## Problem

The app is permanently light. `roadtrip-zion.css:150` already ships a complete
`.mode-dark.theme-roadtrip-zion` block, and `roadtrip-zion-bridge.css` derives every
`--rt-*` chrome role from mode-aware expressions rather than fixed grey steps — so the
theme layer is ready. Nothing applies the class. All three page shells (`index.html`,
`watches.html`, `availability.html`) hardcode `<html class="theme-roadtrip-zion">`, and
there is no user-facing control and no stored preference.

Issue #625 asks for a toggle, persisted per session for anonymous visitors and as a saved
profile setting for signed-in users.

## Approach

One preference with three values, resolved to one of two modes:

```
ThemeChoice = 'light' | 'dark' | 'system'      what the user picked
ThemeMode   = 'light' | 'dark'                 what the DOM gets
resolveMode(choice, osPrefersDark) -> ThemeMode
```

`mode-dark` is applied to `<html>` when the resolved mode is dark. When the choice is
`system`, a `matchMedia('(prefers-color-scheme: dark)')` listener re-resolves live; the
listener is inactive for the two explicit choices.

Anonymous visitors are permanently `system` and get no control. Signing in replaces that
with `profile.theme`; signing out clears the local mirror and returns to `system`.

### Decisions and rejected alternatives

**The control is a segmented control in Settings → Profile, not chrome.** Rejected: a
control in each page's chrome (three placements to maintain, and the map page's chrome is
already dense); a control in chrome *and* in Settings (two write paths for one value).

**Anonymous visitors follow the OS rather than getting their own toggle.** This departs
from the issue's literal "anon user can toggle and persist state on session": anonymous
users never see the Settings button — `AuthRow.tsx:30-40` renders only "Sign in" — so a
toggle for them means new chrome, which the placement decision above rules out. Following
`prefers-color-scheme` serves the same intent (an anonymous visitor in dark gets dark)
with no new surface. Accepted consequence: an anonymous visitor cannot override their OS.

**Three states, not a binary switch.** A switch is what the issue names, but it carries
two states; keeping `system` costs a second switch that disables the first, and produces a
state ("match system" off, "dark" off) that is Light said twice. Rejected: dropping
`system` to get one clean switch — a signed-in user's device in dark mode would then show
white until they visit Settings.

**Live preview, committed on Save.** Clicking a segment repaints immediately; Save
persists; closing the modal without saving reverts to the saved choice. Rejected: writing
on click (one field in the Profile tab would ignore its own Save button, a mixed contract
inside one panel); applying only after Save (a whole-page visual choice made blind).

**The basemap follows the theme until the user overrides it.** The basemap is a separate
saved choice (`basemaps.ts:11`, `localStorage['basemap']`), but the route line and pin
strokes *are* `--rt-*` tokens: in dark, `--rt-map-route` resolves to `--rt-ink` =
`#E9EBEE`, which over a pale basemap is nearly invisible. So dark mode resolves the
basemap to `carto-dark` — already in the registry — for users who have never picked one.
An explicit pick always wins. Rejected: leaving the basemap alone (the app's own overlays
become harder to read, not merely mismatched).

**A local mirror plus a pre-paint script.** The resolved mode is mirrored to
`localStorage` on every settings load, and an inline script in each shell applies the class
before first paint. Rejected: reading only `prefers-color-scheme` at boot (a signed-in
user whose choice differs from their OS gets a white-to-dark flip on every page load);
applying after React mounts (every dark user flashes white on every load).

## Frontend structure

| File | Responsibility |
|---|---|
| `src/lib/theme.ts` *(new)* | Pure. The two types, `resolveMode`, the storage key, and defensive mirror read/write — the same `try/catch` shape as `basemaps.ts:83`, because Safari private mode throws on `localStorage` access rather than returning null. |
| `src/stores/themeStore.ts` *(new)* | Zustand, following `mapStore`. Holds `choice` and resolved `mode`; applies the class, sets the `theme-color` meta to the mode's `--surface-page` (`#FFFFFF` light, `#101215` dark), calls `resetTokenCache()`, owns the `matchMedia` subscription. |
| `src/features/account/ProfilePanel.tsx` | Gains the `SegmentedControl`. `ProfileValues` grows `theme`; `isProfileDirty` and `buildProfilePayload` extend with it, so the control rides the panel's existing single Save path. |
| `src/features/account/SettingsModal.tsx` | On unmount, if the previewed choice differs from the saved one, reapply the saved one. |
| `src/features/map/basemaps.ts` | `DARK_BASEMAP = 'carto-dark'` beside `DEFAULT_BASEMAP`; `initialBasemapKey()` takes the resolved mode and returns the mode's default when nothing is stored. |
| `src/features/map/BasemapPicker.tsx` | An explicit **Auto** option at the top. Selecting it *removes* `localStorage['basemap']` rather than storing the string `"auto"` — absence is already what "follow the theme" means, and a stored sentinel would be a second encoding of the same state. Without this option, "auto" is a state a user can leave but never return to. |
| `index.html`, `watches.html`, `availability.html` | The same inline pre-paint script. |

The control's label is "Appearance", with the segments `Light` / `Dark` / `System` and the
help text "System follows your device setting." It sits below Display name in the Profile
tab. `SegmentedControl` comes from `@ui`; the theme's own rule — selected is basalt, never
the action colour — gives it its appearance with no new CSS.

### The duplicated boot script

The script must be inline to beat first paint: a deferred module script runs after the
browser has already painted the body, and a `<script src>` costs a blocking request. Three
copies of roughly eight lines is the honest cost of three page entries. A test asserts that
all three shells contain it, so the copies cannot silently drift.

It reads `localStorage['rt-theme']`, falls back to `prefers-color-scheme` when absent or
unreadable, and adds `mode-dark` to `document.documentElement` accordingly. It also sets
the `theme-color` meta, which `index.html:15` currently hardcodes to `#ffffff`.

## Data flow

```
boot        inline script: localStorage['rt-theme'] ?? prefers-color-scheme -> <html>
mount       themeStore seeds from the same source (no repaint - already correct)
signed in   useSettings lands -> profile.theme wins -> store updates -> mirror rewritten
change      click a segment -> store applies immediately -> Save button dirties
save        PUT /api/settings/profile { display_name, theme } -> response reseeds panel
close       unsaved -> store reverts to the saved choice
sign out    mirror cleared -> next load follows the OS
```

On any mode change the map must `resetTokenCache()` and then re-apply the paint properties
of the overlay layers it owns — the token cache is memoized per the module's own note, so
without the reset the layers would keep the previous mode's colours. When no basemap is
stored, the resolved basemap key changes too, which flows through `MapProvider`'s existing
`setStyle` path rather than a new one.

**The live preview includes the map.** Previewing Dark swaps the basemap and repaints the
overlays immediately, and reverting on close restores both. Anything less would make the
preview a partial answer to "what does dark look like" on the page where it matters most.

## Backend

- `V51__user_theme.sql`: `theme TEXT NOT NULL DEFAULT 'system'` on `users`, with a
  `CHECK` constraint over `('light', 'dark', 'system')`.
- `ProfileDto` gains `theme: String`. `UpdateProfileRequest` gains `theme: String?`, where
  absent means unchanged — matching how the notifications payload already omits an
  unchanged Slack token.
- Validation and persistence live in `UserSettingsService` and the user repo. No SQL leaves
  the repo and no logic enters the route, per `AGENTS.md`.
- The three legal values are a named constant on the server, not a literal at the call
  site.

Anonymous visitors involve the server not at all.

## Error handling and edge cases

- **`localStorage` unavailable** (Safari private mode, quota): reads and writes are
  wrapped; the app falls back to the OS preference and the choice simply is not persisted
  locally. The signed-in preference still round-trips through the server.
- **An unknown `theme` value from the server** (an older client, a hand-edited row) is
  coerced to `system` client-side rather than throwing.
- **A rejected save** surfaces through the modal's existing `settingsErrorMessage` path;
  the previewed theme stays applied until the modal closes, then reverts, which is the same
  rule as any other unsaved edit.
- **The settings fetch fails** for a signed-in user: the mirror from the last successful
  load still governs, so the page does not flip to light on a transient error.

## Testing

- `resolveMode` over the full choice x OS-preference matrix.
- `themeStore`: applies and removes the class; follows `matchMedia` changes only while the
  choice is `system`; clears the mirror on sign-out.
- `ProfilePanel`: theme participates in dirty tracking; a click previews live.
- `SettingsModal`: closing with an unsaved theme reverts; saving does not.
- `basemaps`: auto-resolution by mode; an explicit stored pick wins over it; the Auto
  option clears the key.
- A test that all three page shells contain the boot script.
- Backend: a bad theme value is rejected with a mapped error code; a valid one round-trips.
- A Storybook story for the Appearance row in both modes — Storybook is the living
  component catalog, and a new shared pattern is not done until it has one.

## Out of scope

Per-page theme overrides, scheduled auto-switching (sunset/sunrise), and any theme beyond
`roadtrip-zion`.

## Open risk

The data colours deliberately do not invert — the token bridge is explicit that layer hues
and the availability vocabulary "are data, not chrome". The campground green (`#2e7d32`)
and supercharger red (`#e82127`) read acceptably against a dark basemap in mockup, but this
must be checked against the real map before shipping. If either fails, the fix belongs in
the data palette rather than in this feature.
