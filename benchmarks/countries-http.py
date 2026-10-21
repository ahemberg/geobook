"""Measure country HTTP reads and bulk confirmation on an isolated synthetic collection."""
import json
import re
import sys
import time
import urllib.request


def measure(url, runs):
    def request(path, data=None):
        req = urllib.request.Request(url + path, data=None if data is None else json.dumps(data).encode(),
                                     method='GET' if data is None else 'PATCH',
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)

    review = request('/api/visits')
    candidates = review['candidates']
    assert review['unresolvedPoints'] == 0 and len(candidates) == 500
    assert all(row['country'] == 'SWE' and len(row['tracks']) == 1 and
               re.fullmatch(r'Benchmark \d{4}', row['tracks'][0]['name']) for row in candidates), \
        'Use only the isolated synthetic benchmark collection'
    visits = [{'country': row['country'], 'date': row['date']} for row in candidates]
    for sample in range(runs):
        started = time.monotonic()
        saved = request('/api/visits/review', {'state': 'confirmed', 'visits': visits})
        assert saved['saved']
        print(json.dumps({'sample': sample + 1, 'operation': 'confirm500',
                          'seconds': time.monotonic() - started}), flush=True)
        for path in ['/api/visits', '/api/countries']:
            started = time.monotonic()
            result = request(path)
            seconds = time.monotonic() - started
            if path == '/api/visits':
                assert len(result['candidates']) == 500
                assert all(row['state'] == 'confirmed' for row in result['candidates'])
            else:
                assert len(result) == 1 and result[0]['recordedDays'] == 500
            print(json.dumps({'sample': sample + 1, 'operation': path,
                              'seconds': seconds}), flush=True)


if __name__ == '__main__':
    assert len(sys.argv) in (2, 3), 'Usage: python3 countries-http.py URL [RUNS]'
    count = int(sys.argv[2]) if len(sys.argv) == 3 else 5
    assert count > 0
    measure(sys.argv[1].rstrip('/'), count)
