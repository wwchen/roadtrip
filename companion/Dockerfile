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

CMD ["node", "--env-file-if-exists=../.env", "src/server.js"]
