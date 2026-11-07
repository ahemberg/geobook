# Documentation screenshots

`heatmap.png` and `heatmap-mobile.png` show the real app with
[heatmap-demo.gpx](heatmap-demo.gpx), a fixture of 12 invented walks in Paris.
The coordinates, names and times were generated for these screenshots. They do
not come from a GPS recording, Takeout archive or the owner's collection. The
straight paths are deliberate and do not describe walking directions.

## Retake the heatmap screenshots

Build the jar with `./mvnw verify`. Start an isolated instance from the repository
root on an unused loopback port, with a new temporary data directory:

```sh
demo_dir=$(mktemp -d)
GEOBOOK_AUTH_MODE=public java -jar target/tracker.jar \
  --server.address=127.0.0.1 --server.port=18123 \
  --geobook.data-directory="$demo_dir"
```

In another terminal, upload only the synthetic fixture:

```sh
curl --fail -F file=@docs/images/heatmap-demo.gpx \
  http://127.0.0.1:18123/api/imports
```

Open `/heatmap?at=48.85500,2.32300,14&limit=15&scale=global` on that instance.
Wait for Building or Updating to disappear and for the map tiles and heat to
finish drawing. The desktop capture uses a 1280 by 800 viewport. The phone
capture uses 430 by 900, zoom 13, with Limit and scale expanded. Keep the colour
legend and OpenStreetMap attribution visible.

Stop the temporary instance after capture. Never point this workflow at an
existing data directory, running deployment or real travel export.
