# syntax=docker/dockerfile:1

# Build context is the repo root: the SPA depends on the shared workspace packages under
# packages/, installed from the root lockfile.

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
COPY packages/i18n/package.json ./packages/i18n/
COPY packages/ui-kit/package.json ./packages/ui-kit/
RUN npm ci --ignore-scripts

COPY packages ./packages
COPY admin ./admin
RUN npm run build -w admin

# --- runtime stage: serve the bundle behind an unprivileged nginx (port 8080) --
FROM nginxinc/nginx-unprivileged:1.30-alpine AS runtime
# The image's start script renders the template into conf.d with envsubst. Only MEDIA_ORIGIN is
# substituted, so nginx's own $variables stay; an empty default keeps the CSP at 'self' alone
# when the deployment does not set it.
ENV MEDIA_ORIGIN="" \
    NGINX_ENVSUBST_FILTER="^MEDIA_ORIGIN$"
COPY admin/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/admin/dist /usr/share/nginx/html
EXPOSE 8080
