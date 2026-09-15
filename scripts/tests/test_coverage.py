"""The coverage gate itself must fail when evidence disappears or a floor is missed."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("coverage_gate", Path(__file__).parents[1] / "check-coverage.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CoverageTest(unittest.TestCase):
    def report(self, covered=8, missed=2):
        return ET.fromstring(f'<report><counter type="LINE" covered="{covered}" missed="{missed}"/></report>')

    def test_exact_floor_passes(self):
        self.assertEqual([], gate.check(self.report(), {"overall": {"LINE": 80}}))

    def test_below_floor_fails_without_rounding_up(self):
        self.assertTrue(gate.check(self.report(799, 201), {"overall": {"LINE": 80}}))

    def test_missing_scope_fails(self):
        self.assertTrue(gate.check(self.report(), {"removed-package": {"LINE": 80}}))

    def test_missing_metric_fails(self):
        self.assertTrue(gate.check(self.report(), {"overall": {"BRANCH": 80}}))

    def test_empty_counter_fails(self):
        self.assertTrue(gate.check(self.report(0, 0), {"overall": {"LINE": 80}}))

    def test_source_floor_counts_partly_executed_lines_as_covered(self):
        report = ET.fromstring('<report><package name="app"><sourcefile name="Example.kt">'
                               '<line nr="1" ci="1" mi="5"/><line nr="2" ci="0" mi="5"/>'
                               '</sourcefile></package></report>')
        self.assertEqual([], gate.check(report, {"app/Example.kt": {"LINE": 50}}))
        self.assertTrue(gate.check(report, {"app/Example.kt": {"LINE": 51}}))
