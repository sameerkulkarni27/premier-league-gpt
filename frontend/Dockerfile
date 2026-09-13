# syntax=docker/dockerfile:1

# --- build stage -------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

# VITE_API_GATEWAY_BASE_URL is baked into the static bundle at build time
# (Vite env vars are compile-time). Override at `docker build --build-arg`
# time if api-gateway isn't reachable at the default.
ARG VITE_API_GATEWAY_BASE_URL=http://localhost:8080
ENV VITE_API_GATEWAY_BASE_URL=$VITE_API_GATEWAY_BASE_URL

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# --- serve stage --------------------------------------------------------
FROM nginx:1.27-alpine AS serve

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
