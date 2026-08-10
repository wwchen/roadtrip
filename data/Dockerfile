FROM alpine:3.23

ARG ROADTRIP_DATA_SHA

LABEL ca.floo.roadtrip.managed="true" \
      ca.floo.roadtrip.component="data"

RUN test -n "${ROADTRIP_DATA_SHA}"

# Keep the versioned dataset separate from the application image.  The build
# context is data/, so the root .dockerignore intentionally has no effect here.
COPY --link us-states.geojson /source/us-states.geojson
COPY --link tesla-superchargers.geojson /source/tesla-superchargers.geojson
COPY --link tesla-locations-us.json /source/tesla-locations-us.json
COPY --link pricing-cache /source/pricing-cache
# One layer per upstream source. Appending a capture should upload that source's
# changed layer, not a fresh 1.7 GB monolith. Adding a new source directory must
# add one line here, making omission visible in code review and CI.
COPY --link raw/apca-accommodation /source/raw/apca-accommodation
COPY --link raw/apca-places /source/raw/apca-places
COPY --link raw/aspira-dictionaries-bc /source/raw/aspira-dictionaries-bc
COPY --link raw/aspira-dictionaries-pc /source/raw/aspira-dictionaries-pc
COPY --link raw/aspira-dictionaries-wa /source/raw/aspira-dictionaries-wa
COPY --link raw/aspira-inventory-bc /source/raw/aspira-inventory-bc
COPY --link raw/aspira-inventory-pc /source/raw/aspira-inventory-pc
COPY --link raw/aspira-inventory-wa /source/raw/aspira-inventory-wa
COPY --link raw/aspira-maps-bc /source/raw/aspira-maps-bc
COPY --link raw/aspira-maps-pc /source/raw/aspira-maps-pc
COPY --link raw/aspira-maps-wa /source/raw/aspira-maps-wa
COPY --link raw/aspira-resources-bc /source/raw/aspira-resources-bc
COPY --link raw/aspira-resources-pc /source/raw/aspira-resources-pc
COPY --link raw/aspira-resources-wa /source/raw/aspira-resources-wa
COPY --link raw/bcparks-strapi /source/raw/bcparks-strapi
COPY --link raw/campflare-campgrounds-export /source/raw/campflare-campgrounds-export
COPY --link raw/campflare-campsites-export /source/raw/campflare-campsites-export
COPY --link raw/osm-pf /source/raw/osm-pf
COPY --link raw/recgov-campground-enrichment /source/raw/recgov-campground-enrichment
COPY --link raw/recgov-campgrounds /source/raw/recgov-campgrounds
COPY --link raw/recgov-campsites /source/raw/recgov-campsites
COPY --link raw/reserveamerica-abpp /source/raw/reserveamerica-abpp
COPY --link raw/reserveamerica-campsites-abpp /source/raw/reserveamerica-campsites-abpp
COPY --link raw/reserveamerica-campsites-ny /source/raw/reserveamerica-campsites-ny
COPY --link raw/reserveamerica-ny /source/raw/reserveamerica-ny
COPY --link raw/reservecalifornia-catalog /source/raw/reservecalifornia-catalog
COPY --link raw/tesla-index /source/raw/tesla-index
COPY --link raw/tesla-locations /source/raw/tesla-locations
COPY --link raw/uscampgrounds /source/raw/uscampgrounds
COPY docker-entrypoint.sh /usr/local/bin/roadtrip-data-init

RUN printf '%s\n' "${ROADTRIP_DATA_SHA}" > /roadtrip-data-sha \
    && chmod 755 /usr/local/bin/roadtrip-data-init

ENTRYPOINT ["/usr/local/bin/roadtrip-data-init"]
