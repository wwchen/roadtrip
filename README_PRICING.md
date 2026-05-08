# Pricing setup

Supercharger pricing is fetched lazily from `tesla.com/api/findus/get-charger-details`
through a local proxy. That endpoint is Akamai-gated — every request needs a valid
`_abck` cookie tied to the calling IP.

## One-time setup (or refresh when expired)

```sh
./refresh-cookies.sh
```

The script walks you through pasting a "Copy as cURL" blob from Chrome DevTools,
extracts the cookie, writes it to `.env`, smoke-tests against a known
Supercharger, and offers to restart the local/Docker server to pick it up.

Behind the scenes:
1. In Chrome, visit <https://www.tesla.com/findus?functionType=supercharger>, click any Supercharger.
2. DevTools → Network tab → find `get-charger-details?...` → right-click → Copy → Copy as cURL.
3. Paste into the script's stdin, press Ctrl-D.

## When cookies expire

Akamai cookies last on the order of a day; they're also IP-bound.
If pricing stops working, popups show `Pricing unavailable (HTTP 403)`.
Re-run `./refresh-cookies.sh`.

## What gets cached

Each site's response (including the `availabilityProfile` congestion histogram and
the `effectivePricebooks` rate schedule) goes to `data/pricing-cache/<slug>.json`.
Delete a file or the whole directory to force a refetch.
