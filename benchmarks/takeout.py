#!/usr/bin/env python3
"""Generate a synthetic, real-sized Google Takeout and import it through the public HTTP API."""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import random
import zipfile
import zlib

from collection import upload

HOME = (59.3293, 18.0686)
WORK = (59.3428, 18.0493)
CITIES = [('Berlin', 52.5200, 13.4050), ('Paris', 48.8566, 2.3522), ('London', 51.5072, -0.1276),
          ('Oslo', 59.9139, 10.7522), ('Copenhagen', 55.6761, 12.5683), ('Barcelona', 41.3874, 2.1686),
          ('Rome', 41.9028, 12.4964), ('Helsinki', 60.1699, 24.9384), ('New York', 40.7128, -74.0060),
          ('Tokyo', 35.6762, 139.6503)]
# Year: (mean seconds between fixes, share of days with data, share of long sleeps in a day).
YEARS = {2013: (110, .95, 0), 2014: (120, .97, 0), 2015: (300, .95, .05), 2016: (900, .85, .2),
         2017: (1200, .8, .3), 2018: (1200, .75, .3), 2019: (1500, .7, .35), 2020: (1500, .7, .35),
         2021: (1800, .65, .4), 2022: (1800, .6, .4)}
FIRST_DAY = dt.date(2013, 3, 1)
LAST_DAY = dt.date(2022, 10, 31)
MONTHS = ['JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE', 'JULY', 'AUGUST', 'SEPTEMBER',
          'OCTOBER', 'NOVEMBER', 'DECEMBER']


def offset(position, rng, metres):
    latitude, longitude = position
    north = rng.gauss(0, metres) / 111_320
    east = rng.gauss(0, metres) / (111_320 * math.cos(math.radians(latitude)))
    return latitude + north, longitude + east


def e7(value):
    return round(value * 10_000_000)


def trips(rng):
    """A few journeys a year, mostly in Europe, with occasional long-haul flights."""
    away = {}
    for year in YEARS:
        for _ in range(rng.randint(4, 7)):
            start = dt.date(year, 1, 1) + dt.timedelta(days=rng.randrange(365))
            city = CITIES[rng.randrange(len(CITIES) - 2)] if rng.random() < .85 else CITIES[rng.randrange(8, 10)]
            for day in range(rng.randint(3, 7)):
                away.setdefault(start + dt.timedelta(days=day), city)
    return away


def plan(date, city, rng):
    """Stops with UTC times for one day. Times are approximate local hours shifted to UTC."""
    utc = dt.datetime(date.year, date.month, date.day, tzinfo=dt.timezone.utc) - dt.timedelta(hours=1)
    if city is None:
        stops = [('Home', HOME, 0, 8), ('Office', WORK, 8.6, 17)]
        if rng.random() < .5:
            stops.append((f'Synthetic Café {rng.randrange(40)}', offset(HOME, rng, 2500), 17.5, 19))
        stops.append(('Home', HOME, 19.5, 24))
    else:
        name, latitude, longitude = city
        centre = (latitude, longitude)
        hotel = offset(centre, random.Random(f'{name}{date.year}'), 1500)
        stops = [(f'Synthetic Hotel {name}', hotel, 0, 9)]
        hour = 9.5
        while hour < 20:
            length = rng.uniform(1, 3)
            stops.append((f'Synthetic Sight {name} {rng.randrange(25)}', offset(centre, rng, 3000), hour, hour + length))
            hour += length + rng.uniform(.3, 1)
        stops.append((f'Synthetic Hotel {name}', hotel, min(hour, 23.5), 24))
    return utc, stops


def position(stops, hour, rng):
    for index, (_, place, start, end) in enumerate(stops):
        if start <= hour <= end:
            return offset(place, rng, 15)
        following = stops[index + 1] if index + 1 < len(stops) else None
        if following and end < hour < following[2]:
            share = (hour - end) / (following[2] - end)
            return offset(tuple(a + (b - a) * share for a, b in zip(place, following[1])), rng, 15)
    return offset(stops[-1][1], rng, 15)


def write_fix(output, first, latitude, longitude, time, accuracy, device, rng):
    record = {'latitudeE7': e7(latitude), 'longitudeE7': e7(longitude), 'accuracy': accuracy}
    if time.year < 2019:
        record['timestampMs'] = str(int(time.timestamp() * 1000))
    else:
        record['timestamp'] = time.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    record['source'] = 'CELL' if accuracy > 200 else rng.choice(['WIFI', 'GPS'])
    record['deviceTag'] = device
    record['platformType'] = 'ANDROID'
    if rng.random() < .4:
        record['activity'] = [{'activity': [{'type': kind, 'confidence': rng.randrange(100)}
                                            for kind in ('STILL', 'ON_FOOT', 'IN_VEHICLE', 'UNKNOWN')],
                               'timestamp': (time - dt.timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')}
                              for seconds in (0, 20, 40)]
    if rng.random() < .3:
        record['altitude'] = rng.randrange(0, 80)
        record['verticalAccuracy'] = rng.randrange(2, 30)
    output.write(('' if first else ',\n') + json.dumps(record, indent=2))


def visit(name, place, start, end):
    return {'placeVisit': {
        'location': {'latitudeE7': e7(place[0]), 'longitudeE7': e7(place[1]), 'placeId': f'synthetic-{zlib.crc32(name.encode())}',
                     'address': 'Synthetic address', 'name': name, 'semanticType': 'TYPE_UNKNOWN',
                     'sourceInfo': {'deviceTag': 1}, 'locationConfidence': 90.0},
        'duration': {'startTimestamp': start.isoformat().replace('+00:00', 'Z'),
                     'endTimestamp': end.isoformat().replace('+00:00', 'Z')},
        'placeConfidence': 'HIGH_CONFIDENCE', 'centerLatE7': e7(place[0]), 'centerLngE7': e7(place[1]),
        'visitConfidence': 90, 'otherCandidateLocations': [
            {'latitudeE7': e7(place[0] + .001), 'longitudeE7': e7(place[1]), 'placeId': 'synthetic-candidate',
             'locationConfidence': 10.0}]}}


def activity(start, end):
    return {'activitySegment': {
        'duration': {'startTimestamp': start.isoformat().replace('+00:00', 'Z'),
                     'endTimestamp': end.isoformat().replace('+00:00', 'Z')},
        'activityType': 'IN_PASSENGER_VEHICLE', 'confidence': 'HIGH'}}


# Generation is one deterministic pass over the decade; splitting it would only move state around.
def generate(directory, scale=1.0, seed=67):
    directory.mkdir(parents=True, exist_ok=True)
    archive_path = directory / 'takeout-synthetic.zip'
    rng = random.Random(seed)
    away = trips(rng)
    semantic = {}
    fixes = 0
    visits = 0
    dates = set()
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        with archive.open('Takeout/Location History (Timeline)/Records.json', 'w', force_zip64=True) as raw:
            output = _Text(raw)
            output.write('{\n  "locations": [')
            first = True
            day = FIRST_DAY
            while day <= LAST_DAY:
                interval, coverage, sleeps = YEARS[day.year]
                if rng.random() < coverage:
                    start, stops = plan(day, away.get(day), rng)
                    hour = rng.uniform(0, 1)
                    secondary = rng.random() < .03
                    while hour < 24:
                        if rng.random() < sleeps * interval / 3600:
                            hour += rng.uniform(2, 6)
                            continue
                        latitude, longitude = position(stops, hour, rng)
                        cell = rng.random()
                        accuracy = rng.randint(5, 45) if cell < .72 else rng.randint(50, 150) if cell < .82 else rng.randint(300, 1800)
                        if accuracy > 200:
                            latitude, longitude = offset((latitude, longitude), rng, accuracy / 2)
                        if rng.random() < .002:
                            latitude, longitude = latitude + rng.choice([-.6, .6]), longitude + rng.choice([-.8, .8])
                        device = 2 if secondary and rng.random() < .15 else 1
                        write_fix(output, first, latitude, longitude, start + dt.timedelta(hours=hour), accuracy, device, rng)
                        first = False
                        fixes += 1
                        dates.add(day)
                        hour += rng.expovariate(1 / (interval * scale)) / 3600
                    month = semantic.setdefault((day.year, day.month), [])
                    for index, (name, place, arrive, leave) in enumerate(stops):
                        if day.year > 2015 and index in (0, len(stops) - 1) or rng.random() > .45:
                            continue
                        month.append(visit(name, place, start + dt.timedelta(hours=arrive), start + dt.timedelta(hours=leave)))
                        visits += 1
                        if index + 1 < len(stops):
                            month.append(activity(start + dt.timedelta(hours=leave), start + dt.timedelta(hours=stops[index + 1][2])))
                day += dt.timedelta(days=1)
            output.write('\n  ]\n}\n')
        for (year, month), objects in sorted(semantic.items()):
            path = f'Takeout/Location History (Timeline)/Semantic Location History/{year}/{year}_{MONTHS[month - 1]}.json'
            archive.writestr(path, json.dumps({'timelineObjects': objects}, indent=2))
        archive.writestr('Takeout/archive_browser.html', '<html><body>Synthetic Takeout</body></html>')
    years = sorted({date.year for date in dates})
    manifest = {'fixes': fixes, 'placeVisits': visits, 'daysWithFixes': len(dates), 'years': years,
                'from': str(FIRST_DAY), 'to': str(LAST_DAY), 'archiveBytes': archive_path.stat().st_size}
    (directory / 'takeout-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest))
    return manifest


class _Text:
    def __init__(self, raw):
        self.raw = raw

    def write(self, text):
        self.raw.write(text.encode())


def import_takeout(directory, url):
    archive = directory / 'takeout-synthetic.zip'
    events, _ = upload(url, [archive], 'application/zip')
    final = events[-1]
    assert final['status'] == 'complete', final
    summary = final['summary']
    assert summary['created'] + summary['updated'] + summary['unchanged'] == final['total'], final
    manifest_path = directory / 'takeout-manifest.json'
    manifest = json.loads(manifest_path.read_text())
    manifest['historyDays'] = final['total']
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['generate', 'import'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--scale', type=float, default=1.0, help='Multiplier for the time between fixes')
    parser.add_argument('--url', default='http://127.0.0.1:18081')
    args = parser.parse_args()
    if args.command == 'generate':
        assert args.scale > 0
        generate(args.directory, args.scale)
    else:
        import_takeout(args.directory, args.url.rstrip('/'))
