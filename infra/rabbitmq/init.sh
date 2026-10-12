#!/bin/sh
# Creates the provider runtime identity after RabbitMQ is healthy. The backend keeps the
# administrator account because it owns topology; this worker identity can only consume
# provider.jobs and publish provider result/progress messages through the provider exchange.
set -eu

: "${RABBITMQ_NODENAME:?}" "${PROVIDER_RABBITMQ_USER:?}" "${PROVIDER_RABBITMQ_PASS:?}"

rabbitmqctl -n "$RABBITMQ_NODENAME" await_startup

if rabbitmqctl -n "$RABBITMQ_NODENAME" --silent list_users \
    | cut -f1 | grep -Fxq "$PROVIDER_RABBITMQ_USER"; then
    rabbitmqctl -n "$RABBITMQ_NODENAME" change_password \
        "$PROVIDER_RABBITMQ_USER" "$PROVIDER_RABBITMQ_PASS"
else
    rabbitmqctl -n "$RABBITMQ_NODENAME" add_user \
        "$PROVIDER_RABBITMQ_USER" "$PROVIDER_RABBITMQ_PASS"
fi

# Remove administrator/management tags if an existing local user was previously over-privileged.
rabbitmqctl -n "$RABBITMQ_NODENAME" set_user_tags "$PROVIDER_RABBITMQ_USER"

# configure, write, read regexes respectively. Topology is declared by the backend, so the
# provider needs no configure permission. It publishes only to exchange `provider` and consumes
# only queue `provider.jobs`.
rabbitmqctl -n "$RABBITMQ_NODENAME" set_permissions -p / \
    "$PROVIDER_RABBITMQ_USER" '^$' '^provider$' '^provider\.jobs$'

echo "rabbitmq-init: provider identity ready"
