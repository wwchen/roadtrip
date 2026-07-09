# Campflare

Campflare availability uses the v2 bulk campground availability endpoint:

```text
POST https://api.campflare.com/v2/campgrounds/availability
Authorization: <CAMPFLARE_API_KEY>
Content-Type: application/json
```

The request body contains `campground_ids`, `start_date`, and `end_date`.
Campflare documents a maximum of 25 campground IDs per request, a 60-day
maximum response window, and availability up to 12 months in the future.

The response is normalized from:

```json
{
  "campgrounds": [
    {
      "campground_id": "campground-id-1",
      "campsite_availability": [
        {
          "campsite_id": "123456",
          "availability": {
            "2026-06-01": "available"
          }
        }
      ]
    }
  ]
}
```

Status mapping:

| Campflare | Internal |
|---|---|
| `available` | `AVAILABLE` |
| `reserved` | `RESERVED` |
| `closed` | `CLOSED` |
| `first-come-first-serve` | `FIRST_COME` |
| `not-yet-released` | `UNKNOWN` |
| `unknown` | `UNKNOWN` |

Operational config:

- `CAMPFLARE_API_KEY` or `CAMPFLARE_TOKEN`: API key.
- `CAMPFLARE_API_BASE`: optional API base URL override; defaults to
  `https://api.campflare.com/v2`.
- `CAMPFLARE_AVAILABILITY_MODE`: `auto` (default), `campflare`, or `recgov`.
  `auto` uses Campflare when an API key is configured and otherwise lets
  linked rec.gov refs serve as the availability fallback. `recgov` forces that
  fallback for Campflare catalog rows that carry a rec.gov alias.
- `ROADTRIP_CACHE_CAMPFLARE_AVAILABILITY_TTL`: optional cache TTL override.
