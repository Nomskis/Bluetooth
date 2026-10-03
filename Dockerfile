# Earshot server: signaling + the browser client. ~60 MB image, one dependency (ws).
FROM node:22-alpine

WORKDIR /app
COPY server/package.json server/package-lock.json ./server/
RUN cd server && npm ci --omit=dev && npm cache clean --force

COPY server/src ./server/src
COPY web ./web

ENV NODE_ENV=production \
    PORT=8080 \
    WEB_ROOT=/app/web

EXPOSE 8080
USER node

HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:${PORT}/healthz || exit 1

CMD ["node", "server/src/index.js"]
