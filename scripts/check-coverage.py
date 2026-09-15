#!/usr/bin/env python3
"""Fail closed on missing coverage and enforce reviewed floors, without hiding UI from totals."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def percentages(node):
    result = {}
    if node.tag == "sourcefile":
        lines = node.findall("line")
        if lines:
            result["LINE"] = sum(int(line.get("ci", 0)) > 0 for line in lines) * 100 / len(lines)
        return result
    for counter in node.findall("counter"):
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        if covered + missed:
            result[counter.get("type")] = covered * 100 / (covered + missed)
    return result


def check(report, floors):
    nodes = {"overall": report}
    nodes.update({p.get("name"): p for p in report.findall("package")})
    nodes.update({p.get("name") + "/" + s.get("name"): s
                  for p in report.findall("package") for s in p.findall("sourcefile")})
    errors = []
    for scope, limits in floors.items():
        if scope not in nodes:
            errors.append(f"Missing coverage scope: {scope}")
            continue
        actual = percentages(nodes[scope])
        for metric, minimum in limits.items():
            value = actual.get(metric)
            if value is None or value < minimum:
                errors.append(f"{scope} {metric}: {value} below {minimum}%")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=ROOT / "app/build/reports/coverage/test/debug/report.xml")
    parser.add_argument("--baseline", type=Path, default=ROOT / "scripts/coverage-baseline.json")
    args = parser.parse_args()
    report = ET.parse(args.report).getroot()  # Missing/malformed reports must fail, never skip the gate.
    floors = json.loads(args.baseline.read_text())
    if not floors:
        raise ValueError("Coverage baseline must not be empty")
    for scope, node in [("overall", report)] + [(p.get("name"), p) for p in report.findall("package")]:
        print(scope, " ".join(f"{metric}={value:.1f}%" for metric, value in percentages(node).items()
                             if metric in ("LINE", "BRANCH")))
    errors = check(report, floors)
    if errors:
        raise SystemExit("Coverage regression:\n" + "\n".join(errors))


if __name__ == "__main__":
    main()
