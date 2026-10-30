#!/usr/bin/env python3
"""Summarise a Takeout smoke import and fail on failed events, per-day warnings or server log noise."""
import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys

ARCHIVE_STATUSES = {"progress", "complete"}
LOG_LEVEL = re.compile(r"^\S+\s+(WARN|ERROR)\b")
KNOWN_LOG_LINES = ["Auth mode: public."]


@dataclass(frozen=True)
class Report:
    statuses: dict
    days: int
    warnings: list
    problems: list


def summarize(events):
    statuses = dict(Counter(event["status"] for event in events))
    completes = [event for event in events if event["status"] == "complete"]
    day_warnings = Counter(warning for event in events
                           if event["status"] not in ARCHIVE_STATUSES
                           for warning in event.get("warnings") or [])
    problems = ([f"failed: {event['name']}: {event.get('message')}"
                 for event in events if event["status"] == "failed"]
                + [f"warning on {count} history day events: {warning}"
                   for warning, count in day_warnings.items()]
                + ([] if completes else ["the import stream has no complete event"]))
    return Report(
        statuses=statuses,
        days=sum(event["status"] not in ARCHIVE_STATUSES for event in events),
        warnings=[warning for event in completes for warning in event.get("warnings") or []],
        problems=problems)


def unexpected_log_lines(lines, known=KNOWN_LOG_LINES):
    return [line for line in lines
            if LOG_LEVEL.match(line) and not any(pattern in line for pattern in known)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path, help="NDJSON response from /api/imports")
    parser.add_argument("log", type=Path, help="Server log")
    parser.add_argument("seconds", type=float, help="Elapsed import time")
    args = parser.parse_args()
    events = [json.loads(line) for line in args.events.read_text().splitlines() if line.strip()]
    report = summarize(events)
    log_lines = unexpected_log_lines(args.log.read_text(errors="replace").splitlines())
    print("Statuses: " + ", ".join(f"{status} {count}" for status, count in report.statuses.items()))
    print(f"History days: {report.days}")
    print(f"Elapsed: {args.seconds:.0f} s")
    print("Warnings:" + "".join(f"\n  {warning}" for warning in report.warnings)
          if report.warnings else "Warnings: none")
    problems = report.problems + [f"server log: {line}" for line in log_lines]
    for problem in problems:
        print(f"FAIL {problem}", file=sys.stderr)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
