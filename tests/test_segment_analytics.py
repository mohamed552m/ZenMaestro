import tempfile
import unittest
from datetime import date
from pathlib import Path
from types import SimpleNamespace

from analytics import AnalyticsLoader, BurnoutCalculator, TaskSnapshot
from database import Database


def snap(task_id, category, segments, *, is_break=False, is_fixed=False):
    return TaskSnapshot(
        task_id=task_id, plan_date="2026-09-30", title=f"T{task_id}",
        category_name=category, priority=3, estimated_minutes=60,
        actual_minutes=None, scheduled_start=segments[0][0] if segments else None,
        scheduled_end=segments[-1][1] if segments else None, status="pending",
        failure_reason=None, completed_at=None, created_at="", weekday=2,
        hour=9, segments=tuple(segments), is_break=is_break,
        is_fixed_time=is_fixed,
    )


class SegmentAnalyticsTests(unittest.TestCase):
    def test_recovery_merges_overlap_and_touching_intervals(self):
        tasks = [
            snap(1, "Study", [("09:00:00", "10:00:00"), ("11:00:00", "11:30:00")]),
            snap(2, "Work", [("09:30:00", "10:30:00")]),
            snap(3, "Break", [("10:30:00", "11:00:00")], is_break=True),
            snap(4, "Work", [("12:00:00", "12:30:00")]),
        ]
        stats = SimpleNamespace(tasks_by_date={"2026-09-30": tasks})
        # 09:00-11:30 merges into one occupied interval; only 11:30-12:00 counts.
        self.assertEqual(30.0, BurnoutCalculator._compute_recovery_time(stats))

    def test_context_switching_uses_segments_and_skips_break_category(self):
        a = snap(1, "Study", [("09:00:00", "09:30:00"), ("10:00:00", "10:30:00")])
        brk = snap(2, "Break", [("09:30:00", "10:00:00")], is_break=True)
        same = SimpleNamespace(tasks_by_date={"d": [a, brk]})
        self.assertEqual(0.0, BurnoutCalculator._compute_context_switching(same))
        b = snap(3, "Work", [("10:30:00", "11:00:00")])
        different = SimpleNamespace(tasks_by_date={"d": [a, brk, b]})
        self.assertEqual(1.0, BurnoutCalculator._compute_context_switching(different))
        unscheduled = snap(4, "Other", [])
        with_unscheduled = SimpleNamespace(tasks_by_date={"d": [a, brk, b, unscheduled]})
        self.assertEqual(1.0, BurnoutCalculator._compute_context_switching(with_unscheduled))

    def test_schedule_density_uses_merged_segment_occupancy(self):
        tasks = [
            snap(1, "Study", [("09:00:00", "10:00:00")]),
            snap(2, "Work", [("09:30:00", "10:30:00")]),
            snap(3, "Work", []),
        ]
        self.assertEqual(90.0, BurnoutCalculator._occupied_minutes(tasks))

    def test_loader_keeps_one_snapshot_for_split_logical_task(self):
        tempdir = tempfile.TemporaryDirectory()
        db = None
        try:
            path = Path(tempdir.name) / "test.db"
            db = Database(f"sqlite:///{path.as_posix()}")
            db.execute("INSERT INTO users (user_id,email,display_name) VALUES ('u','u@x.com','U')")
            db.execute(
                "INSERT INTO plans (user_id,plan_date,raw_input) VALUES ('u',?,'x')",
                (date.today().isoformat(),),
            )
            db.connection.commit()
            plan = db.fetch_one("SELECT plan_id FROM plans")["plan_id"]
            task = db.add_task(plan, "u", "Split", estimated_minutes=60)
            revision = db.get_plan_by_id(plan, "u")["schedule_revision"]
            db.replace_plan_segments(plan, "u", {task: [
                {"segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "09:30:00"},
                {"segment_index": 1, "scheduled_start": "10:00:00", "scheduled_end": "10:30:00"},
            ]}, revision)
            snapshots = AnalyticsLoader(db).load_snapshots("u", 30)
            self.assertEqual(1, len(snapshots))
            self.assertEqual(2, len(snapshots[0].segments))
        finally:
            if db is not None:
                db.close()
            tempdir.cleanup()


if __name__ == "__main__":
    unittest.main()
