# Dashboard sources

Four dashboards here are copies of published ones, and two are written for this
repo. Checked 2026-10-01.

Every vendored file is generated from the downloaded original, not edited by
hand. The generation does three things:

1. replaces `${DS_PROMETHEUS}` with `prometheus`, the provisioned datasource uid;
2. rewrites every Prometheus datasource reference, including legacy string
   references and uids the author left behind, to
   `{"type": "prometheus", "uid": "prometheus"}`;
3. sets `id` to `null`.

To update one, download the new revision, apply the same steps, and update its
row below.

## Vendored

None of the four grafana.com listings states a licence. Only the RabbitMQ
dashboard has a published upstream source with a licence file.

| File | Origin | Revision | Author (grafana.com org) | Licence | Downloaded file sha256 |
|---|---|---|---|---|---|
| `spring-boot-19004.json` | <https://grafana.com/grafana/dashboards/19004>, from `…/api/dashboards/19004/revisions/latest/download` | grafana.com revision 1 (JSON `version` 75), updated 2023-06-20. Adapted by its author from dashboard 10280 | pierreconstantial | none stated on the listing | `7434355e09ce2c9e911d9e63b7cf7f992e000ba50a31bad225b721e5b2588eba` |
| `jvm-micrometer-4701.json` | <https://grafana.com/grafana/dashboards/4701> | revision 10 (JSON `version` 33), updated 2024-11-22 | mweirauch | none stated on the listing | `c8ed623c921cda086a8446c06f333e8ec19072a4f209a5b6a06dcb186140ae73` |
| `rabbitmq-overview-10991.json` | <https://grafana.com/grafana/dashboards/10991>. Its source is `deps/rabbitmq_prometheus/docker/grafana/dashboards/RabbitMQ-Overview.json` in <https://github.com/rabbitmq/rabbitmq-server> | revision 15 (JSON `version` 16), updated 2025-04-17 | RabbitMQ | MPL-2.0: `deps/rabbitmq_prometheus/LICENSE` says "This package is licensed under the MPL 2.0" | `f98a21fe50c85c1da41d1a9a429d04aa5b137c9025753945f1b53e8f6fc1e6a2` |
| `postgresql-9628.json` | <https://grafana.com/grafana/dashboards/9628> | revision 8 (JSON `version` 1), updated 2024-12-06 | Lucas Estienne (lstn) | none stated on the listing | `521bac34065bcadedf255fc384550ae0528e9a74e91b4475e583b6afe9c39cc7` |

## Written for this repo

- `media-engine.json`: jobs by status, `media.jobs` queue depth, late results
  discarded, and steps reaped by code.
- `silo.json`: capacity, objects, buckets, S3 requests, errors and traffic,
  from `/minio/v2/metrics/cluster`.

It replaces dashboard 13502 (the MinIO dashboard). That dashboard is no longer
on grafana.com (its API answers 404). The copies kept in
<https://github.com/minio/minio> and <https://github.com/pgsty/silo> sit in
AGPL-3.0 repositories, so no copy of it ships here.
