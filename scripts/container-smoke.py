#!/usr/bin/env python3
"""Exercise the Compose deployment through HTTP, optionally running Playwright."""

import http.client
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = b'''<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>Container smoke</name><trkseg>
<trkpt lat="59.32" lon="18.07"><time>2025-03-30T00:59:00Z</time></trkpt>
<trkpt lat="59.321" lon="18.071"><time>2025-03-30T01:00:00Z</time></trkpt>
</trkseg></trk></gpx>'''


def main():
    image = sys.argv[1]
    with tempfile.TemporaryDirectory(prefix="geobook-container-") as temporary:
        data = Path(temporary) / "data"
        data.mkdir()
        env = dict(os.environ, GEOBOOK_IMAGE=image, GEOBOOK_DATA_DIRECTORY=str(data),
                   GEOBOOK_UID=str(os.getuid()), GEOBOOK_GID=str(os.getgid()),
                   GEOBOOK_BIND_ADDRESS="127.0.0.1", GEOBOOK_PORT="0",
                   GEOBOOK_TILES_URL="https://tile.openstreetmap.org/{z}/{x}/{y}.png?smoke=1",
                   GEOBOOK_TILES_ATTRIBUTION="© OpenStreetMap contributors",
                   GEOBOOK_TILES_ATTRIBUTIONURL="https://www.openstreetmap.org/copyright?smoke=1")
        command = ["docker", "compose", "--env-file", "/dev/null", "-f",
                   str(ROOT / "compose.yaml"), "-p", Path(temporary).name]

        def compose(*args, check=True):
            return subprocess.run(command + list(args), env=env, check=check,
                                  text=True, stdout=subprocess.PIPE).stdout.strip()

        def request(path, body=None, method="GET", content_type="application/json"):
            query = urllib.request.Request(base + path, data=body, method=method,
                                           headers={"Content-Type": content_type})
            with urllib.request.urlopen(query, timeout=120) as response:
                return response.read()

        def ready():
            nonlocal base
            base = "http://" + compose("port", "geobook", "8080")
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                try:
                    request("/api/tracks")
                    return
                except (OSError, http.client.HTTPException):
                    time.sleep(0.25)
            raise AssertionError("Container did not become ready within 120 seconds")

        def verify():
            detail = json.loads(request(f"/api/tracks/{track_id}"))
            assert detail["name"] == "Saved container track", detail
            assert detail["colour"] == "#123abc", detail
            assert detail["visible"] is False, detail
            assert detail["points"][0]["timezone"] == "Europe/Stockholm", detail
            assert detail["points"][0]["localTime"] == "2025-03-30T01:59:00+01:00", detail
            assert detail["points"][1]["localTime"] == "2025-03-30T03:00:00+02:00", detail
            assert request(f"/api/originals/{original_id}") == ORIGINAL
            overview = json.loads(request("/api/tracks?from=2025-03-30&to=2025-03-30"))
            assert any(track["id"] == track_id and track["pointCount"] == 2
                       for track in overview), overview

        base = ""
        try:
            started = time.monotonic()
            compose("up", "-d", "--pull", "never")
            ready()
            print(f"Fresh startup ready in {time.monotonic() - started:.2f}s", flush=True)
            assert json.loads(request("/api/tracks")) == []
            assert b'<div id="root">' in request("/map")
            config = json.loads(request("/api/map-config"))
            assert config["url"] == env["GEOBOOK_TILES_URL"], config
            assert config["attribution"] == env["GEOBOOK_TILES_ATTRIBUTION"], config
            assert config["attributionUrl"] == env["GEOBOOK_TILES_ATTRIBUTIONURL"], config
            for path in ("/api/unknown", "/assets/missing.js", "/missing.css"):
                try:
                    request(path)
                except urllib.error.HTTPError as error:
                    assert error.code == 404, (path, error.code)
                else:
                    raise AssertionError(f"{path} should return 404")
            assert b"Open Database License" in request("/notices/timezones.md")
            assert b"Source Sans 3" in request("/notices/frontend.txt")
            assert b"Eclipse Public License" in request("/notices/backend.txt")
            multipart = (b'--fixture\r\nContent-Disposition: form-data; name="file"; '
                         b'filename="synthetic.gpx"\r\nContent-Type: application/gpx+xml\r\n\r\n'
                         + ORIGINAL + b'\r\n--fixture--\r\n')
            started = time.monotonic()
            imported = json.loads(request("/api/tracks", multipart, "POST",
                                          "multipart/form-data; boundary=fixture"))
            print(f"First timed import with geographic resources: {time.monotonic() - started:.2f}s",
                  flush=True)
            track_id, original_id = imported["trackId"], imported["originalId"]
            request(f"/api/tracks/{track_id}",
                    b'{"name":"Saved container track","colour":"#123abc","visible":false}',
                    "PATCH")
            verify()
            container = compose("ps", "-q", "geobook")
            subprocess.run(["docker", "stats", "--no-stream", "--format",
                            "{{.MemUsage}} | CPU {{.CPUPerc}}", container], check=True)
            compose("restart")
            ready()
            verify()
            compose("up", "-d", "--force-recreate", "--pull", "never")
            ready()
            assert compose("ps", "-q", "geobook") != container
            verify()
            compose("stop")
            backup = Path(temporary) / "backup"
            shutil.copytree(data, backup)
            shutil.rmtree(data)
            shutil.copytree(backup, data)
            compose("start")
            ready()
            verify()
            print("HTTP, native SQLite, timezone data, restart, recreation and restore passed.",
                  flush=True)
            if "--browser" in sys.argv[2:]:
                frontend = ROOT / "src/main/frontend"
                node = frontend / "node/node"
                subprocess.run([str(node) if node.exists() else "node",
                                "node_modules/@playwright/test/cli.js", "test"],
                               cwd=frontend, env=dict(env, GEOBOOK_TEST_URL=base), check=True)
        finally:
            print(compose("logs", "--no-color", check=False), flush=True)
            compose("down", check=False)


if __name__ == "__main__":
    main()
