#!/bin/sh

# label:domain pairs. For each, the matching HTTPS vhost is "<label>-https.conf".
# HTTPS vhosts are disabled until their cert exists, so nginx can always start.
DOMAINS="app:app.mateu.io auth:auth.mateu.io grafana:grafana.mateu.io"
EMAIL="miguelperezcolom@gmail.com"
WEBROOT="/var/www/certbot"

mkdir -p "$WEBROOT"

# Disable any HTTPS vhost whose cert is not present yet.
for pair in $DOMAINS; do
  label="${pair%%:*}"
  domain="${pair#*:}"
  conf="/etc/nginx/conf.d/${label}-https.conf"
  if [ ! -f "/etc/letsencrypt/live/$domain/fullchain.pem" ]; then
    [ -f "$conf" ] && mv "$conf" "$conf.disabled"
  fi
done

# Start cron (daemon) for certbot renewal.
/usr/sbin/crond -l 8 || true

# In background: issue missing certs and enable their HTTPS vhosts, without killing nginx.
(
  for pair in $DOMAINS; do
    label="${pair%%:*}"
    domain="${pair#*:}"
    conf="/etc/nginx/conf.d/${label}-https.conf"

    if [ ! -f "/etc/letsencrypt/live/$domain/fullchain.pem" ]; then
      echo "No cert found for $domain. Issuing..."
      certbot certonly --webroot -w "$WEBROOT" -d "$domain" \
        -m "$EMAIL" --agree-tos --no-eff-email --non-interactive \
        && echo "Certificate issued for $domain." \
        || echo "Certbot failed for $domain (will retry later)."
    fi

    if [ -f "/etc/letsencrypt/live/$domain/fullchain.pem" ]; then
      [ -f "$conf.disabled" ] && mv "$conf.disabled" "$conf"
    fi
  done

  nginx -t && nginx -s reload || true
) &

# Start nginx ONCE, in foreground.
exec nginx -g 'daemon off;'
