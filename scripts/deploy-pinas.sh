#!/usr/bin/env bash
# Deploy a merged commit to pinas: build, back up, replace the container, smoke-test and record it.
# Usage: scripts/deploy-pinas.sh <commit>
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <commit>" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
readonly host=pinas
commit=$(git rev-parse --verify "$1^{commit}")
readonly commit
readonly short=${commit:0:12}
readonly release="geobook/releases/$short"
readonly image="geobook:$short"
backup=""
rollback=""

on_exit() {
  local status=$?
  if [ "$status" -ne 0 ] && [ -n "$backup" ]; then
    printf 'Deploy failed. Backup: %s\nRoll back: %s\n' "$backup" "$rollback" >&2
  fi
}
trap on_exit EXIT

if [ -n "$(git status --porcelain)" ]; then
  echo "The checkout is not clean. Commit or stash changes first." >&2
  exit 1
fi
if [ "$(git rev-parse HEAD)" != "$commit" ]; then
  echo "HEAD is not $commit. Check out the commit first: git switch --detach $commit" >&2
  exit 1
fi

runs=$(gh run list --commit "$commit" --workflow backend-tests.yml --status success \
  --json databaseId --jq length || echo 0)
if [ "${runs:-0}" -gt 0 ]; then
  echo "CI passed for $short, so packaging without tests."
  ./mvnw -DskipTests package
else
  ./mvnw verify
fi
jar=$(sed -n 's|^COPY \(target/[^ ]*\.jar\) .*|\1|p' Dockerfile)
jar_sha256=$(sha256sum "$jar" | cut -d ' ' -f 1)
pr=$(git log -1 --format=%s "$commit" | sed -n 's/.*(#\([0-9]*\))$/\1/p')

ssh "$host" mkdir -p "$release"
tar -c Dockerfile LICENSE compose.yaml src/main/docker/geobook "$jar" | ssh "$host" tar -x -C "$release"
ssh "$host" "cd $release && docker build --tag $image ."
architecture=$(ssh "$host" docker image inspect --format '{{.Architecture}}' "$image")
if [ "$architecture" != arm64 ]; then
  echo "$image is $architecture, not arm64." >&2
  exit 1
fi
image_id=$(ssh "$host" docker image inspect --format '{{.Id}}' "$image")

if ssh "$host" diff -u geobook/compose.yaml "$release/compose.yaml"; then
  echo "compose.yaml is unchanged."
else
  echo "Adopting the release compose.yaml shown above."
fi

previous=$(ssh "$host" "sed -n 's/^GEOBOOK_IMAGE=//p' geobook/.env")
now=$(date -u +%s)
stamp=$(date -u -d "@$now" +%Y%m%dT%H%M%SZ)
name="pre-$short-$stamp"
ssh "$host" bash -s -- "$name" <<'EOF'
set -euo pipefail
cd ~/geobook
docker compose stop
sudo mkdir -p "backups/$1"
if ! sudo cp -a data .env compose.yaml "backups/$1/"; then
  echo "The backup failed, so the old container is starting again." >&2
  docker compose start
  exit 1
fi
EOF
backup="~/geobook/backups/$name"
rollback="ssh $host 'cd ~/geobook && docker compose stop && sudo mv data data-failed-$stamp"
rollback+=" && sudo cp -a backups/$name/data data && cp backups/$name/.env backups/$name/compose.yaml ."
rollback+=" && docker compose up -d --force-recreate'"

ssh "$host" bash -s -- "$short" "$image" <<'EOF'
set -euo pipefail
cd ~/geobook
cp "releases/$1/compose.yaml" compose.yaml
grep -q '^GEOBOOK_IMAGE=' .env
sed -i "s|^GEOBOOK_IMAGE=.*|GEOBOOK_IMAGE=$2|" .env
docker compose up -d --force-recreate
for _ in $(seq 90); do
  logs=$(docker compose logs --no-color geobook)
  if grep -q 'APPLICATION FAILED' <<<"$logs"; then
    tail -n 50 <<<"$logs"
    exit 1
  fi
  if grep 'Started TrackerApplication' <<<"$logs"; then
    exit 0
  fi
  sleep 2
done
echo "Geobook did not start within 180 seconds." >&2
tail -n 50 <<<"$logs"
exit 1
EOF

tracks=$(ssh "$host" bash -s -- "$short" <<'EOF'
set -euo pipefail
cd ~/geobook
address=$(docker compose port geobook 8080)
base="http://${address/0.0.0.0/127.0.0.1}"
created=$(docker compose exec -T geobook geobook auth add "deploy-smoke-$1" read \
  "$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)")
id=$(sed -n 's/.*(id \([0-9]*\),.*/\1/p' <<<"$created")
token=$(tail -n 1 <<<"$created")
trap 'docker compose exec -T geobook geobook auth rm "$id" >/dev/null' EXIT
for path in /map /api/tracks/years /api/history-display /api/countries /api/visits /api/originals/1; do
  curl -fsS -H "Authorization: Bearer $token" "$base$path" >/dev/null
done
curl -fsS -H "Authorization: Bearer $token" "$base/api/tracks"
EOF
)
counts=$(python3 -c '
import json, sys
tracks = json.loads(sys.argv[1])
history = sum(track["kind"] == "history" for track in tracks)
print(f"{len(tracks) - history} tracks, {history} history days")' "$tracks")

record=$(ssh "$host" cat geobook/deployment.json || echo '{}')
python3 -c '
import json, sys
record = json.loads(sys.argv[1])
commit, pr, image, image_id, jar_sha256, previous, backup, deployed_at = sys.argv[2:]
record.update(commit=commit, pr=int(pr) if pr else None, image=image, imageId=image_id,
              jarSha256=jar_sha256, previousImage=previous, backup=backup, deployedAt=deployed_at)
print(json.dumps(record, indent=2))' "$record" "$commit" "$pr" "$image" "$image_id" "$jar_sha256" \
  "$previous" "$backup" "$(date -u -d "@$now" +%Y-%m-%dT%H:%M:%SZ)" |
  ssh "$host" "cat > geobook/deployment.json && cp geobook/deployment.json $release/deployment.json"

echo "Deployed $image from $commit."
echo "Backup: $backup"
echo "Collection: $counts"
echo "Roll back: $rollback"
