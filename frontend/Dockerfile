# syntax=docker/dockerfile:1

# Build context is the repo root: the SPA depends on the shared workspace package
# packages/api-client, installed from the root lockfile.

# --- build stage: produce the static SPA bundle -----------------------------
# The bundle is platform-independent, so it builds natively on the build host.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app

# Manifests first, so the dependency layer is reused until a manifest or the lockfile changes.
# npm ci needs every workspace's manifest, not only this app's.
COPY package.json package-lock.json ./
COPY frontend/package.json ./frontend/
COPY admin/package.json ./admin/
COPY packages/api-client/package.json ./packages/api-client/
COPY packages/ui-kit/package.json ./packages/ui-kit/
RUN npm ci --ignore-scripts

COPY packages ./packages
COPY frontend ./frontend
RUN npm run build -w frontend

# --- runtime stage: serve the bundle behind an unprivileged nginx (port 8080) --
FROM nginxinc/nginx-unprivileged:1.30-alpine AS runtime
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/frontend/dist /usr/share/nginx/html
EXPOSE 8080
