#!/bin/sh
# Runs at container start (before nginx) via the nginx image's entrypoint.d hook.
# Writes the active DNS resolver — taken from the container's own /etc/resolv.conf — into
# an http-level snippet. This is what lets the variable `proxy_pass` in default.conf
# resolve backend Service names at request time.
#   - Docker Compose: nameserver 127.0.0.11 (embedded DNS)
#   - Kubernetes:     the CoreDNS ClusterIP
# Same script, correct value in both.
set -e

resolver=$(awk '/^nameserver/ { print $2; exit }' /etc/resolv.conf)

if [ -n "$resolver" ]; then
    echo "resolver ${resolver} valid=10s ipv6=off;" > /etc/nginx/conf.d/00-resolver.conf
    echo "05-resolver.sh: using DNS resolver ${resolver}"
else
    echo "05-resolver.sh: no nameserver in /etc/resolv.conf; leaving nginx default" >&2
fi
