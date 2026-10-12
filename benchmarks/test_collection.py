"""Small generator check; never imports the five-million-point workload."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from collection import generate


class CollectionTest(unittest.TestCase):
    def test_shareable_fixture_has_source_breaks_gaps_and_known_dates(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with contextlib.redirect_stdout(io.StringIO()):
                generate(directory, 2, 100)
            document = ET.parse(directory / 'collection-0000.gpx')
            namespaces = {'g': 'http://www.topografix.com/GPX/1/1'}
            tracks = document.findall('g:trk', namespaces)
            self.assertEqual(len(tracks), 2)
            self.assertEqual(len(tracks[0].findall('g:trkseg', namespaces)), 2)
            points = tracks[0].findall('.//g:trkpt', namespaces)
            self.assertEqual(len(points), 100)
            self.assertEqual(points[0].find('g:time', namespaces).text, '2024-01-01T10:00:00Z')
            self.assertEqual(points[74].find('g:time', namespaces).text, '2024-01-01T10:01:14Z')
            self.assertEqual(points[75].find('g:time', namespaces).text, '2024-01-01T10:11:15Z')
            self.assertEqual(json.loads((directory / 'manifest.json').read_text())['points'], 200)
            with self.assertRaises(FileExistsError):
                generate(directory, 2, 100)


if __name__ == '__main__':
    unittest.main()
