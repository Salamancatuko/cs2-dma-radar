# ============================================================
# CS2 DMA Radar - Server image (multi-stage)
# Stage 1 builds the Vue web frontend, stage 2 runs the Node relay.
# Build from the project root:
#   docker build -f server/Dockerfile -t cs2-dma-radar-server .
# ============================================================

# --- Stage 1: build the web frontend -------------------------
FROM node:20-alpine AS web-builder
WORKDIR /build/web
ENV WEB_OUT_DIR=/build/web/dist
COPY server/web/package.json server/web/package-lock.json* ./
RUN npm ci || npm install
COPY server/web/ .
RUN npm run build

# --- Stage 2: runtime ----------------------------------------
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY server/package.json server/package-lock.json* ./
RUN npm ci --omit=dev || npm install --omit=dev
COPY server/src ./src
COPY --from=web-builder /build/web/dist ./public

ENV PORT=27081 \
    HOST=0.0.0.0 \
    BROADCAST_INTERVAL_MS=16

EXPOSE 27081
CMD ["node", "src/index.js"]
