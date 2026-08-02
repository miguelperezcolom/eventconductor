FROM nginx:stable-alpine-perl

RUN apk add --no-cache bash certbot certbot-nginx

COPY daily/* /etc/periodic/daily/
RUN chmod +x /etc/periodic/daily/*

COPY conf.d/* /etc/nginx/conf.d/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]