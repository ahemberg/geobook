"""Heat rebuild polling against scripted summaries; never talks to a server."""
import unittest

from collection import wait_for_heatmap


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def summaries(*states):
    remaining = list(states)
    return lambda: remaining.pop(0)


def summary(building, built_at, cells=7):
    return {'building': building, 'builtAt': built_at, 'cells': cells, 'hottest': [59.3, 18.0]}


class WaitForHeatmapTest(unittest.TestCase):
    def test_waits_until_the_build_finishes_and_a_newer_build_is_served(self):
        clock = Clock()
        read = summaries(summary(True, 'old'), summary(False, 'old'), summary(True, 'old'), summary(False, 'new', 42))
        result = wait_for_heatmap(read, 'old', clock=clock, sleep=clock.sleep)
        self.assertEqual(result, {'secondsAfterImport': 1.5, 'builtAt': 'new', 'cells': 42, 'polls': 4})

    def test_accepts_the_served_build_when_nothing_was_imported(self):
        clock = Clock()
        read = summaries(summary(True, 'old'), summary(False, 'old'))
        self.assertEqual(wait_for_heatmap(read, None, clock=clock, sleep=clock.sleep)['builtAt'], 'old')

    def test_never_accepts_a_missing_build(self):
        clock = Clock()
        read = summaries(summary(False, None), summary(False, None), summary(False, 'first'))
        self.assertEqual(wait_for_heatmap(read, None, clock=clock, sleep=clock.sleep)['polls'], 3)

    def test_fails_when_the_build_outlasts_the_timeout(self):
        clock = Clock()
        with self.assertRaisesRegex(AssertionError, 'still building after 2 s'):
            wait_for_heatmap(lambda: summary(True, 'old'), 'old', timeout=2, clock=clock, sleep=clock.sleep)


if __name__ == '__main__':
    unittest.main()
