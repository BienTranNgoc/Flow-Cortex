# syntax=docker/dockerfile:1

# --- build stage: produce the static SPA bundle -----------------------------
FROM node:22-alpine AS build
WORKDIR /app

# Vite inlines VITE_* envs at BUILD time. Inside compose the SPA talks to the
# backend on the SAME origin (nginx proxies /api -> backend), so this defaults
# to a relative path rather than a hard-coded host.
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- runtime stage: serve the bundle behind nginx --------------------------
FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
