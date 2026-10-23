"""Small Takeout generator check; the real-sized archive is only generated for benchmarks."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from takeout import generate


class TakeoutTest(unittest.TestCase):
    def test_synthetic_takeout_has_dense_early_and_sparse_late_years_with_visits(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with contextlib.redirect_stdout(io.StringIO()):
                manifest = generate(directory, scale=20)
            with zipfile.ZipFile(directory / 'takeout-synthetic.zip') as archive:
                names = archive.namelist()
                records = json.loads(archive.read('Takeout/Location History (Timeline)/Records.json'))
                semantic = [name for name in names if '/Semantic Location History/' in name]
                visits = sum(1 for name in semantic for item in json.loads(archive.read(name))['timelineObjects']
                             if 'placeVisit' in item)
            locations = records['locations']
            self.assertEqual(len(locations), manifest['fixes'])
            self.assertEqual(visits, manifest['placeVisits'])
            self.assertEqual(manifest['years'], list(range(2013, 2023)))
            years = [str(fix.get('timestamp', '')[:4]) or None for fix in locations]
            early = sum(1 for fix in locations if 'timestampMs' in fix and int(fix['timestampMs']) < 1420070400000)
            late = years.count('2022')
            self.assertGreater(early, 10 * late)
            self.assertTrue(any(fix['accuracy'] > 200 for fix in locations))
            self.assertEqual({fix['deviceTag'] for fix in locations}, {1, 2})
            with self.assertRaises(FileExistsError):
                with contextlib.redirect_stdout(io.StringIO()):
                    generate(directory, scale=20)


if __name__ == '__main__':
    unittest.main()
