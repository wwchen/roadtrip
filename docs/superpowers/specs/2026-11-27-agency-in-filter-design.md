# Agency selection moves into the campground filter

Status: approved 2026-11-27, after reviewing the Storybook prototype (`Prototypes/Agency in Filters`).

## Intent

When searching and filtering campgrounds, users reach for the Filter button, then have to reach over to the
map's Layers panel to narrow by managing agency. Agency is a filter, not a layer. It moves into the filter;
Layers keeps only what is drawn (the campground switch, stops, basemap).

## Goals

- An **Agency** multi-select dropdown in the filter panel, ordered by campgrounds in view (most first), with
  an "All agencies" row and a search box once more than 10 agencies are in view.
- **Show-only semantics**: nothing picked means every agency; picking agencies shows only those. Panning into
  a new region does not add agencies the user did not pick.
- The agency pick counts toward the filter badge and is reset by **Clear**.
- Layers panel loses the agency rows and says nothing about the agency filter.
- Design pass on the filter panel:
  - The pill reads **Filter**, and the "Then check dates" hint goes.
  - The result count and **Clear** sit on the pill row, and the separate "Campgrounds · N in view" row goes.
  - Every control gets the same small label (Agency, Site type, Group size, Amenities, Start/End date).
  - Group size shows **Any** when off.
  - A divider separates the catalog filters from the dates.

## Non-goals and anti-goals

- No federal/state/provincial grouping. No source field carries that level, so it would be inferred.
- No UI-side merging of duplicate agency names. Duplicates are data bugs, handled in the
  "Fix duplicate agency names at the data source" session.
- No server-side agency filter. Agency narrowing stays client-side, off the agency each pin carries.

## Decisions

- **State lives in `campgroundFilterStore`** as `agencies: string[]` (the picked set). `mapStore.hiddenAgencies`,
  `setAgencyHidden`, and `selectIsAgencyVisible` are removed.
- **Two filter counts.** `selectActiveFilterCount` (badge) includes the agency pick. `selectCatalogFilterCount`
  (site type, group size, amenities) decides whether the server search carries a filter and whether the map
  pins follow the search's matching ids. An agency-only pick never sends an empty filter DTO.
- **Scope follows the rest of the filter: browse mode only.** The TopBar publishes the agency pick to the map
  (`mapStore.campgroundFilterAgencies`) under the same condition it publishes `campgroundFilterIds`, and clears
  it on unmount. While a route owns the list, the Filter panel is hidden, so neither the catalog filter nor the
  agency pick applies to the corridor list or its pins. Before this change the legend's agency switches also
  narrowed the route list; that coupling goes with the legend rows.
- **Dropdown counts** are viewport campgrounds narrowed by the catalog filter (the same ids the map paints),
  before the agency pick. A picked agency no longer in view stays listed with 0 so it can be unticked.
- **Shared links**: opening a shared campground whose agency is outside a non-empty pick adds that agency to the
  pick, so the linked pin is drawn. This replaces un-hiding it in the legend.
- **Reusable pieces**:
  - `MultiSelect` is a new `@ui` primitive. It is domain-free and takes ordered options with counts.
  - The field label and the "button drawn as a field" box become shared `@ui` styles (`field.css`). The date
    fields, the agency dropdown and the group-size stepper all use them.
- The menu renders right after its trigger, so it follows it in tab order. It uses fixed positioning, because
  the topbar panel scrolls (`overflow-y: auto`) and would clip an absolutely positioned menu. A portal was
  tried first and dropped: it put the menu at the end of the page, out of keyboard reach.
- The menu has no count on its "All agencies" row. Pins are capped per category in dense views, so a sum of
  pin counts can disagree with the server's "N in view" beside it. Per-agency counts share that limit.

## Approaches considered

1. **Chips with "+N more"** (prototyped first). Rejected by the user in favour of a dropdown: chips with long
   agency names wrap into several rows and cost height.
2. **Multi-select dropdown ordered by count** (chosen).
3. **Hide-these semantics** (today's legend behaviour, all ticked by default). Rejected: in a filter it reads as
   "already filtered", and it does not match how the other filters narrow.

## Left to the builder

Copy wording beyond the labels above. Exact spacing values (keep the existing topbar scale).
