FROM mcr.microsoft.com/playwright:v1.60.0-noble

WORKDIR /app

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
