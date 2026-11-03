#!/bin/sh
# Per-instance admit counters (must sum to the k6 foyer_admitted count) and Redis slowlog.
set -e
for p in 18081 18082; do
  echo "== gateway :$p"
  curl -s "http://localhost:$p/actuator/prometheus" | grep -E '^foyer_(admit|enter|poll)_total' || true
done
echo "== redis"
docker compose exec -T redis redis-cli info stats | grep -E 'total_commands_processed|instantaneous_ops_per_sec'
docker compose exec -T redis redis-cli slowlog len
docker compose exec -T redis redis-cli slowlog get 3
docker compose exec -T redis redis-cli zcard 'foyer:{concert-2026}:queue'
