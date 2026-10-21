"""Profile the actual VisitRepository SELECTs against a read-only synthetic database."""
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import sys
import textwrap
import time


def profile(database, source):
    queries = [textwrap.dedent(query).strip()
               for query in re.findall(r'"""(.*?)"""', source.read_text(), re.S)
               if query.strip().startswith('SELECT')]
    assert len(queries) == 4, 'Expected candidates, unresolved points, summaries and validation'
    with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as connection:
        print(json.dumps({'sqlite': sqlite3.sqlite_version,
                          'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest()}), flush=True)
        keys = set()
        for name, query in zip(['candidates', 'unresolved', 'summaries'], queries[:3]):
            arguments = (None, None, None)
            plan = connection.execute('EXPLAIN QUERY PLAN ' + query, arguments).fetchall()
            for sample in range(2):
                started = time.monotonic()
                rows = connection.execute(query, arguments).fetchall()
                seconds = time.monotonic() - started
                print(json.dumps({'query': name, 'sample': sample + 1, 'seconds': seconds,
                                  'rows': len(rows), 'resultSha256': hashlib.sha256(
                                      json.dumps(rows).encode()).hexdigest(), 'plan': plan}), flush=True)
                if name == 'candidates':
                    keys = {(row[0], row[1]) for row in rows}
        assert len(keys) == 500, 'Use the default 500-day synthetic collection'
        for sample in range(2):
            started = time.monotonic()
            present = sum(bool(connection.execute(queries[3], key).fetchone()[0])
                          for key in sorted(keys))
            seconds = time.monotonic() - started
            assert present == 500, 'All candidate days must retain recorded evidence'
            print(json.dumps({'query': 'validate500', 'sample': sample + 1, 'seconds': seconds,
                              'daysPresent': present, 'plan': connection.execute(
                                  'EXPLAIN QUERY PLAN ' + queries[3], sorted(keys)[0]).fetchall()}), flush=True)


if __name__ == '__main__':
    assert len(sys.argv) == 3, 'Usage: python3 countries-sql.py DATABASE VisitRepository.java'
    profile(Path(sys.argv[1]), Path(sys.argv[2]))
