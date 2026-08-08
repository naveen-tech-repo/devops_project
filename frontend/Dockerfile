# Single stage — the SPA is static, no build step. nginx-unprivileged runs as a non-root
# user and listens on 8080, matching the runAsNonRoot pattern used by the Java services.
FROM nginxinc/nginx-unprivileged:1.27-alpine

ARG BUILD_TAG=local
LABEL org.opencontainers.image.version=$BUILD_TAG

# Server config (rendered into conf.d at start) + shared proxy snippet.
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY proxy_common.conf /etc/nginx/proxy_common.conf

# Startup hook that detects the DNS resolver from /etc/resolv.conf. Runs before nginx.
COPY --chmod=0755 docker-entrypoint.d/05-resolver.sh /docker-entrypoint.d/05-resolver.sh

# Static assets.
COPY src/ /usr/share/nginx/html/

EXPOSE 8080
