"""Rebuild the pinned offline country resource using Python's standard library."""
import gzip
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_admin_0_countries.geojson"
SHA256 = "239eec57ac17f100a11e2536cffc56752c318b50ae765b0918ff7aab4ce8f255"


def polygon(rings):
    assert all(len(ring) >= 4 and ring[0] == ring[-1] for ring in rings)
    return "(" + ",".join(
        "(" + ",".join(f"{x} {y}" for x, y in ring) + ")" for ring in rings
    ) + ")"


def main():
    with urlopen(URL, timeout=60) as response:
        source = response.read()
    assert hashlib.sha256(source).hexdigest() == SHA256, "Upstream data changed"
    features = json.loads(source)["features"]
    assert len(features) == 258
    assert len({f["properties"]["ADM0_A3"] for f in features}) == 258
    rows = []
    for feature in features:
        properties, geometry = feature["properties"], feature["geometry"]
        assert geometry["type"] in {"Polygon", "MultiPolygon"}
        coordinates = geometry["coordinates"]
        polygons = [coordinates] if geometry["type"] == "Polygon" else coordinates
        wkt = "MULTIPOLYGON(" + ",".join(polygon(p) for p in polygons) + ")"
        rows.append("\t".join([properties["ADM0_A3"], properties["NAME_EN"], wkt]))
    target = Path(__file__).resolve().parents[1] / "src/main/resources/geography/countries.tsv.gz"
    target.write_bytes(gzip.compress(("\n".join(sorted(rows)) + "\n").encode(), mtime=0))
    print(f"Bundled {len(rows)} countries, {target.stat().st_size} bytes")


if __name__ == "__main__":
    main()
