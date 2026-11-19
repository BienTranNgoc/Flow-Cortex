#!/bin/sh
# Creates the provider runtime identity after RabbitMQ is healthy. The backend keeps the
# administrator account because it owns topology; this worker identity, shared by
# provider-worker and provider-video-worker, can only consume provider.jobs and
# provider.video.jobs, configure/consume per-worker control reply queues under
# provider.control.reply.*, and publish through the provider exchange.
set -eu

: "${RABBITMQ_NODENAME:?}" "${PROVIDER_RABBITMQ_USER:?}" "${PROVIDER_RABBITMQ_PASS:?}"

# provider-worker puts both values in its AMQP URL as is, and they are embedded in JSON below.
case "${PROVIDER_RABBITMQ_USER}${PROVIDER_RABBITMQ_PASS}" in
    *[!A-Za-z0-9._~-]*)
        echo "rabbitmq-init: PROVIDER_RABBITMQ_USER/PROVIDER_RABBITMQ_PASS may use only letters, digits and . _ ~ -" >&2
        exit 1
        ;;
    *) ;;
esac

rabbitmqctl -n "$RABBITMQ_NODENAME" await_startup

# add_user/change_password take the password as an argument, and their stdin prompt needs a
# terminal, so the identity goes in as a definitions file: a private temp file whose path is
# the only argument. Importing creates the user or replaces its password, clears its tags
# (an earlier over-privileged local user loses them) and sets its permissions, so a re-run
# converges. Permissions are configure, write, read: only per-worker exclusive reply queues under
# `provider.control.reply.*` may be configured/read alongside `provider.jobs` and
# `provider.video.jobs`; publishing stays restricted to exchange `provider`.
umask 077
DEFS=$(mktemp)
trap 'rm -f "$DEFS"' EXIT
printf '%s\n' \
    "{\"users\":[{\"name\":\"$PROVIDER_RABBITMQ_USER\",\"password\":\"$PROVIDER_RABBITMQ_PASS\",\"tags\":[]}]," \
    "\"permissions\":[{\"user\":\"$PROVIDER_RABBITMQ_USER\",\"vhost\":\"/\"," \
    '"configure":"^provider\\.control\\.reply\\.[A-Za-z0-9._-]+$","write":"^provider$","read":"^(provider\\.(video\\.)?jobs|provider\\.control\\.reply\\.[A-Za-z0-9._-]+)$"}]}' >"$DEFS"
rabbitmqctl -n "$RABBITMQ_NODENAME" import_definitions --format json "$DEFS"

echo "rabbitmq-init: provider identity ready"
