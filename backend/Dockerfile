# RakshakX backend — zero npm dependencies, so this is a single stage with no
# build step: just Node's built-in http/crypto/sqlite modules and our own code.
FROM node:22-alpine

# node:sqlite needs Node >= 22.5 and is still "experimental" (prints a one-line
# warning to stderr on boot) but stable enough for this server's needs.
RUN node --version

WORKDIR /app

# No package-lock.json / node_modules to install — copy the source directly.
COPY . .

# Run as a non-root user, and make sure it can create/write the sqlite file
# under /app/data (the default DB_PATH, overridable via env).
RUN addgroup -S rakshakx && adduser -S rakshakx -G rakshakx \
  && mkdir -p /app/data \
  && chown -R rakshakx:rakshakx /app
USER rakshakx

ENV NODE_ENV=production
ENV PORT=8787
ENV DB_PATH=/app/data/rakshakx.db

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 8787) + '/').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "server.js"]
