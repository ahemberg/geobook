"""Takeout smoke report, against hand-written import events and server logs."""
import unittest

from takeout_report import summarize, unexpected_log_lines


def day(date, status="imported", warnings=(), message=None):
    return {"status": status, "name": date, "kind": "history", "warnings": list(warnings),
            "message": message}


def complete(warnings=(), created=2):
    return {"status": "complete", "name": "takeout.tgz", "kind": "history",
            "warnings": list(warnings),
            "summary": {"created": created, "updated": 0, "unchanged": 0}}


PROGRESS = {"status": "progress", "name": "takeout.tgz", "kind": "history", "warnings": []}


class SummarizeTest(unittest.TestCase):
    def test_a_clean_import_counts_statuses_and_history_days(self):
        report = summarize([PROGRESS, day("2017-01-24"), day("2017-01-25", "updated"),
                            complete(["31 fixes remain only in the original export."])])
        self.assertEqual(report.statuses, {"progress": 1, "imported": 1, "updated": 1,
                                           "complete": 1})
        self.assertEqual(report.days, 2)
        self.assertEqual(report.warnings, ["31 fixes remain only in the original export."])
        self.assertEqual(report.problems, [])

    def test_a_failed_archive_or_day_is_a_problem(self):
        report = summarize([{"status": "failed", "name": "takeout.tgz", "kind": "history",
                             "warnings": [], "message": "A place visit has no position."}])
        self.assertEqual(report.problems, ["failed: takeout.tgz: A place visit has no position.",
                                           "the import stream has no complete event"])
        self.assertEqual(report.days, 0)
        report = summarize([PROGRESS, day("2017-01-24", "failed", message="Not saved."),
                            complete()])
        self.assertEqual(report.problems, ["failed: 2017-01-24: Not saved."])

    def test_a_warning_on_a_history_day_event_is_a_problem(self):
        warning = "9 fixes at 0, 0 remain only in the original export."
        report = summarize([PROGRESS, day("2017-01-24", warnings=[warning]),
                            day("2017-01-25", warnings=[warning]), complete([warning])])
        self.assertEqual(report.problems, [f"warning on 2 history day events: {warning}"])


class LogTest(unittest.TestCase):
    def test_only_unknown_warn_and_error_lines_are_reported(self):
        lines = [
            "2026-10-30T10:00:00.000Z  INFO 1 --- [tracker] [main] Started TrackerApplication",
            "2026-10-30T10:00:00.000Z  WARN 1 --- [tracker] [main] o.s.b.a.j.JpaBaseConfiguration "
            ": spring.jpa.open-in-view is enabled by default.",
            "2026-10-30T10:01:00.000Z ERROR 1 --- [tracker] [nio-1-exec-1] o.a.c.c.C.[.[.[/] "
            ": Servlet.service() threw exception: Broken pipe",
            "2026-10-30T10:01:00.000Z  WARN 1 --- [tracker] [nio-1-exec-1] Something else",
        ]
        self.assertEqual(unexpected_log_lines(lines, ["spring.jpa.open-in-view"]),
                         [lines[2], lines[3]])


if __name__ == "__main__":
    unittest.main()
