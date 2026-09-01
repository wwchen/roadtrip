# LOCKSTEP: this tag and companion/package.json's exact "playwright" version
# must move together. The image ships the browser binaries; the npm package
# decides which build it looks for. A mismatch fails at launchPersistentContext
# with a missing-executable error, which surfaces as recgov_auth_check_exception
# on every /login and /verify.
FROM mcr.microsoft.com/playwright:v1.62.1-noble

WORKDIR /app

LABEL ca.floo.roadtrip.managed="true" \
      ca.floo.roadtrip.component="recgov-companion"

COPY package.json package-lock.json ./
RUN npm ci

COPY src ./src

ENV NODE_ENV=production
ENV HEADLESS=true
ENV COMPANION_HOST=0.0.0.0
ENV COMPANION_PORT=8770
ENV COMPANION_BROWSER_PROFILE=/var/lib/campsite-companion/browser-session

EXPOSE 8770

# No --env-file: the companion needs no secrets (its Rec.gov session lives in
# the browser-profile volume), and there is no plaintext dotenv to read.
CMD ["node", "src/server.js"]
