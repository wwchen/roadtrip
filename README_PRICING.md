# Pricing setup

Supercharger pricing is fetched lazily from `tesla.com/api/findus/get-charger-details`
through a local proxy. That endpoint is Akamai-gated — every request needs a valid
`_abck` cookie tied to the calling IP.

## One-time setup

1. In Chrome, visit <https://www.tesla.com/findus?functionType=supercharger>. Click any
   Supercharger. This warms Akamai's cookies.
2. Open DevTools → Network tab. Find the `get-charger-details?...` request.
3. Right-click → Copy → Copy as cURL. You only need the `-b '...'` cookie string.
4. Paste into `.env`:
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   ```
   (one line, semicolon-separated, no quotes). `.env` is gitignored.
5. Start the server:
   ```
   python3 server.py
   ```
6. Open <http://localhost:8765/> and click a Supercharger pin. First click per site
   hits Tesla; subsequent clicks within 30 days come from `data/pricing-cache/`.

## When cookies expire

Akamai cookies last on the order of a day to a week; they're also IP-bound.
If pricing stops working, the popup will say `Pricing unavailable (HTTP 403)`.
Redo steps 1–4.

## What gets cached

Each site's response (including the `availabilityProfile` congestion histogram and
the `effectivePricebooks` rate schedule) goes to `data/pricing-cache/<slug>.json`.
Delete a file or the whole directory to force a refetch.
