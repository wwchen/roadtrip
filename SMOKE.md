# Real-Device Smoke Checklist

Run this on a real iPhone before each `make deploy`. Tests cover the trip-critical
golden paths: open the map, find a Supercharger or campground, tap, decide, navigate
out. If any item fails, the deploy waits until it passes.

Target device: iPhone Safari on cellular (LTE or 5G — disable Wi-Fi). Cellular is
the regression catcher; Wi-Fi hides slow-load and gzip-not-applied bugs.

URL: <https://roadtrip.floo.ca>

## 0. Health (sanity check before everything else)

- [ ] `curl https://roadtrip.floo.ca/api/health` returns
      `{"status":"ok","pricing_cache_count":<N>,"now":<epoch>}` with
      `pricing_cache_count` >1000. (Cookie freshness is *not* surfaced here —
      the live backend never calls Tesla; cache health is the only signal.)

## 1. Cold load

- [ ] Open in fresh tab (close previous, swipe up). Map tiles render within 3 s.
- [ ] No JavaScript errors in remote Web Inspector console (Mac → Develop → iPhone).
- [ ] DevTools Network tab: `/api/pois?bbox=…` responses show
      `Content-Encoding: gzip` and `Content-Type: application/geo+json` (or
      `application/json`). Static `state-parks.geojson` (PAD-US polygons,
      still served from `/data/`) is also gzipped — body ~900 KB, not 3.9 MB.
- [ ] Layer toggles in the legend respond on first tap (not the second).

## 2. Geolocation + nearest-to-me

- [ ] Tap the geolocate (compass) button. iOS prompts for location once;
      after granting, a blue dot appears within 5 s.
- [ ] Map recenters on you. Blue dot stays visible after pan (custom marker, not
      flashing system puck).
- [ ] Open search panel. The "Sort by nearest" checkbox is enabled (label
      reads "Sort by nearest", not "Sort by nearest (location off)").
- [ ] Type "banff" — top result has a distance pill (e.g. "1124 km"). Toggling
      the checkbox reorders results.

## 3. Tap targets (the original bug)

- [ ] Pinch-zoom to a city with mixed Supercharger + campground + Planet Fitness
      pins (Vancouver works). Tap each pin type in turn — every tap opens its
      popup. **No missed taps**, even on small dots.
- [ ] Tap a national-park polygon at z=8 (centroid visible). Opens the park
      popup, not the polygon-fill background.
- [ ] Tapping a second pin while a popup is already open: previous popup
      dismisses. Only one popup visible at a time.

## 4. Supercharger popup → Tesla

- [ ] Tap any Supercharger pin (try Banff or Canmore). Popup shows status pill,
      stalls, kW, address, **pricing card** (loads within ~2 s — uses pre-warmed
      cache).
- [ ] "Open in Tesla" button: tap. Opens tesla.com/findus in a new tab,
      **centered on the chosen Supercharger** (not zoomed out to USA).
- [ ] No "Access Denied" Akamai page.

## 5. Campground popup → Reserve

### 5a. AB Parks Canada (federal)

- [ ] Search "Tunnel Mountain" → tap result → popup opens.
- [ ] Popup shows: name, "Banff National Park" parent, season string,
      reservable badge.
- [ ] Links row: `parks.canada.ca ↗` and `reservation.pc.gc.ca ↗`. Tap
      reservation.pc.gc.ca; opens cleanly.

### 5b. AB provincial

- [ ] Search "Boulton Creek" → tap result.
- [ ] Popup links row: `albertaparks.ca ↗` and `reservecamping.alberta.ca ↗`.
      Both open the right destinations.

### 5c. US federal (regression check)

- [ ] Pan to the Olympic Peninsula. Tap a federal campground. Popup links
      row contains `recreation.gov ↗`, not Parks Canada/Alberta links.

## 6. Offline-tolerance rough check

- [ ] Toggle Airplane Mode after the map fully loads. Pan a region you've
      already visited — tiles + dots stay rendered (browser cache).
- [ ] Disable Airplane Mode. Tap a Supercharger pin: pricing loads from
      `/api/pricing/{slug}` (cache hit) within 1 s.

## 7. Battery / heat

- [ ] Map idle (open, no interaction) for 5 min: phone doesn't get hot.
      Tile rendering should pause when nothing is happening.

---

## Quick fixes if something fails

| Failure | Likely cause | Action |
| --- | --- | --- |
| `/api/pois` or state-parks.geojson served uncompressed | gzip middleware not deployed or Cloudflare stripping | Check Ktor `Compression` config in `backend/.../Main.kt`; verify `Vary: Accept-Encoding` arrives at the client |
| Supercharger popup says "Pricing not yet cached" everywhere | offline refresh hasn't been run / cache empty | `make refresh-superchargers` once cookies are fresh (`make refresh-cookies`) |
| Supercharger popup pricing missing for one site | site not yet crawled by offline worker | wait for next refresh, or `make refresh-superchargers` to re-run |
| Tesla button → Access Denied | Akamai bot wall (rare; cookies bound to wrong IP) | `make refresh-cookies` |
| No popup on tap (zooms instead) | Hit layer above visual layer was stripped by edit | `git diff index.html` for `pf-points-hit`/`sc-points-hit`/`np-pts-hit`/`sp-pts-hit` |
| Geolocate dot doesn't appear | iOS denied permission silently | iOS Settings → Safari → Location → While Using App |

After a real-device pass, deploy:
```
make deploy
```
