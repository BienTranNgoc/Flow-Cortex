# syntax=docker/dockerfile:1

# --- build stage: produce the static SPA bundle -----------------------------
FROM node:22-alpine AS build
WORKDIR /app

# Vite inlines VITE_* at build time; inside compose the SPA is same-origin (nginx proxies /api).
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

# --- runtime stage: serve the bundle behind an unprivileged nginx (port 8080) --
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
