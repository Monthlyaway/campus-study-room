"""Fail-closed evidence gates shared by the GitLab integration orchestrator."""
import re
import xml.etree.ElementTree as ET


def require_unittest_report(path):
    text = path.read_text()
    summary = re.search(r"Ran ([1-9][0-9]*) tests? in [0-9.]+s", text)
    if not summary or not re.search(r"^OK$", text, re.MULTILINE) or "skipped=" in text:
        raise RuntimeError("HTTP suite did not execute nonzero, unskipped tests: " + path.name)
    return int(summary.group(1))


def require_junit_report(path):
    evidence = ET.parse(path).getroot()
    count = int(evidence.attrib["tests"])
    if count < 1 or any(int(evidence.attrib.get(key, 0)) for key in ("skipped", "errors", "failures")):
        raise RuntimeError("Persistence suite skipped or failed: " + path.name)
    return count
