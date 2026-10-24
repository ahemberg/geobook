#!/usr/bin/env python3
"""Generate synthetic GPX and import it through the public streaming HTTP API."""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import threading
import time
import urllib.request


def generate(directory, tracks, points):
    directory.mkdir(parents=True, exist_ok=True)
    for first in range(0, tracks, 10):
        with (directory / f'collection-{first:04d}.gpx').open('x') as output:
            output.write('<gpx version="1.1" creator="Geobook synthetic benchmark" xmlns="http://www.topografix.com/GPX/1/1">')
            for track in range(first, min(first + 10, tracks)):
                start = dt.datetime(2024, 1, 1, 10, tzinfo=dt.timezone.utc) + dt.timedelta(days=track)
                output.write(f'<trk><name>Benchmark {track:04d}</name><trkseg>')
                for point in range(points):
                    if point == points // 2:
                        output.write('</trkseg><trkseg>')
                    angle = point * 4 * math.pi / points
                    lat = 59.32 + (track % 20) * .001 + .005 * math.sin(angle)
                    lon = 18.04 + (track // 20) * .001 + .01 * math.cos(angle)
                    seconds = point + (600 if point >= points * 3 // 4 else 0)
                    timestamp = (start + dt.timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
                    output.write(f'<trkpt lat="{lat:.7f}" lon="{lon:.7f}"><ele>{point % 100}</ele><time>{timestamp}</time></trkpt>')
                output.write('</trkseg></trk>')
            output.write('</gpx>')
    manifest = {'tracks': tracks, 'pointsPerTrack': points, 'points': tracks * points,
                'from': '2024-01-01', 'to': '2024-01-01', 'filteredTracks': 1,
                'filteredPoints': points, 'filteredName': 'Benchmark 0000'}
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest))


class ReadProbe:
    """Reads the complete collection every five seconds while an import runs."""

    def __init__(self, url):
        self.url = url
        self.stop = threading.Event()
        self.reads = []
        self.failures = []
        self.worker = threading.Thread(target=self.probe)

    def probe(self):
        while not self.stop.is_set():
            started = time.monotonic()
            try:
                with urllib.request.urlopen(self.url + '/api/tracks', timeout=60) as response:
                    tracks = json.load(response)
                self.reads.append({'seconds': time.monotonic() - started, 'tracks': len(tracks)})
            except Exception as error:
                self.failures.append(str(error))
            self.stop.wait(5)

    def __enter__(self):
        self.worker.start()
        return self

    def __exit__(self, *ignored):
        self.stop.set()
        self.worker.join()


def post(url, path, content_type, started, on_event):
    boundary = 'geobook-benchmark'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f'Content-Type: {content_type}\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    request = urllib.request.Request(url + '/api/imports', data=body,
                headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
    completed = False
    with urllib.request.urlopen(request, timeout=3600) as response:
        for line in response:
            event = json.loads(line)
            on_event(event)
            print(json.dumps({'elapsed': time.monotonic() - started, 'file': path.name, **event}), flush=True)
            if event['status'] == 'failed':
                raise RuntimeError(event)
            completed |= event['status'] == 'complete'
    assert completed, f'Incomplete import: {path}'


def upload(url, paths, content_type):
    """Imports files in order and prints one final JSON line with timings and outcomes."""
    events = []
    started = time.monotonic()
    first_progress = None
    outcomes = {}

    def record(event):
        nonlocal first_progress
        if first_progress is None:
            first_progress = time.monotonic() - started
        outcomes[event['status']] = outcomes.get(event['status'], 0) + 1
        events.append(event)

    with ReadProbe(url) as probe:
        try:
            for path in paths:
                post(url, path, content_type, started, record)
        finally:
            print(json.dumps({'importSeconds': time.monotonic() - started, 'firstProgressSeconds': first_progress,
                              'outcomes': outcomes, 'collectionReads': probe.reads,
                              'readFailures': probe.failures}), flush=True)
    assert not probe.failures, probe.failures
    return events, outcomes


def import_collection(directory, url):
    paths = sorted(directory.glob('collection-*.gpx'))
    assert paths, 'Generate the collection before importing it'
    manifest = json.loads((directory / 'manifest.json').read_text())
    _, outcomes = upload(url, paths, 'application/gpx+xml')
    assert outcomes.get('imported', 0) + outcomes.get('duplicate', 0) == manifest['tracks'], outcomes


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['generate', 'import'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--tracks', type=int, default=500)
    parser.add_argument('--points', type=int, default=10000)
    parser.add_argument('--url', default='http://127.0.0.1:18081')
    args = parser.parse_args()
    if args.command == 'generate':
        assert args.tracks > 0 and args.points >= 4
        generate(args.directory, args.tracks, args.points)
    else:
        import_collection(args.directory, args.url.rstrip('/'))
