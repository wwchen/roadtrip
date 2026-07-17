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

- `roadtrip.campflare.api-key` or `roadtrip.campflare.token`: API key. The
  default `application.yaml` values read `${CAMPFLARE_API_KEY:}` /
  `${CAMPFLARE_TOKEN:}` so secrets stay outside the file.
- `roadtrip.campflare.api-base-url`: optional API base URL override; defaults to
  `https://api.campflare.com/v2`.
- `roadtrip.cache.campflare-availability.ttl`: optional cache TTL override.

Campflare stays registered in the availability-provider registry even when no
API key is configured. In that state the adapter declines Campflare refs, so
availability resolution can continue through linked fallback refs such as
rec.gov aliases without a Campflare-specific branch in the service layer.
