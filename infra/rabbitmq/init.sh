#!/bin/sh
# Creates the worker runtime identities after RabbitMQ is healthy. The backend keeps the
# administrator account because it owns topology. The provider identity, shared by
# provider-worker and provider-video-worker, can only consume provider.jobs and
# provider.video.jobs, configure/consume per-worker control reply queues under
# provider.control.reply.*, and publish through the provider exchange.
# The fetch identity, used by fetch-worker alone (it parses content from the internet), can only
# consume media.fetch.jobs and publish media result/progress messages through the media exchange.
set -eu

: "${RABBITMQ_NODENAME:?}" "${PROVIDER_RABBITMQ_USER:?}" "${PROVIDER_RABBITMQ_PASS:?}"
: "${FETCH_RABBITMQ_USER:?}" "${FETCH_RABBITMQ_PASS:?}"

# The workers put these values in their AMQP URLs as is, and they are embedded in JSON below.
case "${PROVIDER_RABBITMQ_USER}${PROVIDER_RABBITMQ_PASS}${FETCH_RABBITMQ_USER}${FETCH_RABBITMQ_PASS}" in
    *[!A-Za-z0-9._~-]*)
        echo "rabbitmq-init: the PROVIDER_RABBITMQ_* and FETCH_RABBITMQ_* values may use only letters, digits and . _ ~ -" >&2
        exit 1
        ;;
    *) ;;
esac

# One definitions entry per user name: two identities sharing a name would overwrite each
# other's permissions.
if [ "$PROVIDER_RABBITMQ_USER" = "$FETCH_RABBITMQ_USER" ]; then
    echo "rabbitmq-init: PROVIDER_RABBITMQ_USER and FETCH_RABBITMQ_USER must differ" >&2
    exit 1
fi

rabbitmqctl -n "$RABBITMQ_NODENAME" await_startup

# add_user/change_password take the password as an argument, and their stdin prompt needs a
# terminal, so the identities go in as a definitions file: a private temp file whose path is
# the only argument. Importing creates each user or replaces its password, clears its tags
# (an earlier over-privileged local user loses them) and sets its permissions, so a re-run
# converges. Permissions are configure, write, read: topology is declared by the backend, so
# the fetch user has no configure, and the provider user configures only per-worker exclusive
# reply queues under `provider.control.reply.*`. The provider user publishes only to exchange
# `provider` and consumes queues `provider.jobs`, `provider.video.jobs` and its reply queues;
# the fetch user publishes only to exchange `media` and consumes only queue `media.fetch.jobs`.
umask 077
DEFS=$(mktemp)
trap 'rm -f "$DEFS"' EXIT
printf '%s\n' \
    "{\"users\":[{\"name\":\"$PROVIDER_RABBITMQ_USER\",\"password\":\"$PROVIDER_RABBITMQ_PASS\",\"tags\":[]}," \
    "{\"name\":\"$FETCH_RABBITMQ_USER\",\"password\":\"$FETCH_RABBITMQ_PASS\",\"tags\":[]}]," \
    "\"permissions\":[{\"user\":\"$PROVIDER_RABBITMQ_USER\",\"vhost\":\"/\"," \
    '"configure":"^provider\\.control\\.reply\\.[A-Za-z0-9._-]+$","write":"^provider$","read":"^(provider\\.(video\\.)?jobs|provider\\.control\\.reply\\.[A-Za-z0-9._-]+)$"},' \
    "{\"user\":\"$FETCH_RABBITMQ_USER\",\"vhost\":\"/\"," \
    '"configure":"^$","write":"^media$","read":"^media\\.fetch\\.jobs$"}]}' >"$DEFS"
rabbitmqctl -n "$RABBITMQ_NODENAME" import_definitions --format json "$DEFS"

echo "rabbitmq-init: provider and fetch identities ready"
