"""Dropped dub segments are reported; more than one in five fails the dub."""

from __future__ import annotations

from flowcortex_worker.assembly.drops import MAX_DROP_RATIO, DropReport, drop_report


def test_nothing_dropped_is_ok_with_an_empty_list() -> None:
    report = drop_report(10, [])

    assert report == DropReport(ok=True, total=10, dropped=[])
    assert report.count == 0


def test_one_of_ten_missing_is_reported_and_ok() -> None:
    report = drop_report(10, [(4, "missing")])

    assert report.ok
    assert report.count == 1
    assert report.dropped == [{"segIdx": 4, "reason": "missing"}]


def test_a_ratio_at_the_limit_passes() -> None:
    assert MAX_DROP_RATIO == 0.2
    assert drop_report(10, [(1, "missing"), (2, "unreadable")]).ok
    assert drop_report(5, [(0, "missing")]).ok
    assert drop_report(15, [(0, "missing"), (1, "missing"), (2, "missing")]).ok


def test_three_of_ten_fail() -> None:
    report = drop_report(10, [(1, "missing"), (2, "missing"), (3, "unreadable")])

    assert not report.ok
    assert report.count == 3
    assert report.total == 10


def test_the_only_segment_dropped_fails() -> None:
    assert not drop_report(1, [(0, "unreadable")]).ok


def test_reasons_are_only_missing_or_unreadable() -> None:
    report = drop_report(10, [(1, "missing"), (2, "unreadable")])

    assert {row["reason"] for row in report.dropped} == {"missing", "unreadable"}
