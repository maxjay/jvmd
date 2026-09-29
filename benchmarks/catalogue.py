#!/usr/bin/env python3
"""Verify the recovered source workbook and its reviewable, lossless catalogue export."""
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
WORKBOOK_SHA256 = "6fa34e33381384e6a831c6715ee2a76bfdba9f064a4927e9660b831fa6d21796"
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
SCENARIO_FIELDS = ["id", "domain", "family", "setup", "actions", "correctness", "measurement", "variants"]
API_FIELDS = ["id", "domain", "method", "kind", "direction", "input", "output", "scenarioIds", "testUse", "sourceUrl"]


def sheet_rows(archive, number, coordinates=False):
    shared = []
    if "xl/sharedStrings.xml" in archive.namelist():
        shared = ["".join(s.itertext()) for s in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
    rows = []
    for row in ET.fromstring(archive.read(f"xl/worksheets/sheet{number}.xml")).findall(".//s:row", NS):
        cells = {}
        for c in row.findall("s:c", NS):
            if c.get("t") == "inlineStr":
                value = "".join(t.text or "" for t in c.findall(".//s:t", NS))
            else:
                value = c.findtext("s:v", default="", namespaces=NS)
                if c.get("t") == "s":
                    value = shared[int(value)]
            if value != "":
                cells[c.attrib["r"] if coordinates else re.sub(r"\d", "", c.attrib["r"])] = value
        if cells:
            rows.append(cells)
    return rows


def verify(root=ROOT):
    workbook = root / "JDTLS_API_and_Test_Scenarios.xlsx"
    if len(workbook.read_bytes()) != 30151 or hashlib.sha256(workbook.read_bytes()).hexdigest() != WORKBOOK_SHA256:
        raise ValueError("Workbook differs from the recovered intact original")
    catalogue = json.loads((root / "catalogue.json").read_text())
    with zipfile.ZipFile(workbook) as archive:
        if archive.testzip() is not None:
            raise ValueError("Workbook ZIP integrity failure")
        if sheet_rows(archive, 3, coordinates=True) != catalogue["guideCellValues"]:
            raise ValueError("Guide export differs from workbook cell values")
        for number, key, fields, pattern, count in [
            (1, "scenarios", SCENARIO_FIELDS, r"[A-Z]{3,4}-\d{2}", 28),
            (2, "apis", API_FIELDS, r"API-\d{3}", 129),
        ]:
            source = [dict(zip(fields, [r.get(chr(65 + i), "") for i in range(len(fields))]))
                      for r in sheet_rows(archive, number) if re.fullmatch(pattern, r.get("A", ""))]
            if len(source) != count or source != catalogue[key]:
                raise ValueError(f"{key}: export is missing or changes original cell values")
            if len({r["id"] for r in source}) != count:
                raise ValueError(f"{key}: duplicate IDs")
    ids = {s["id"] for s in catalogue["scenarios"]}
    counts = {role: sum(a["testUse"] == role for a in catalogue["apis"])
              for role in ("Scenario target", "Setup / support", "Harness support", "Reference only")}
    if list(counts.values()) != [106, 13, 6, 4]:
        raise ValueError("API role totals changed")
    for api in catalogue["apis"]:
        if any(s != "—" and s not in ids for s in api["scenarioIds"].split(", ")):
            raise ValueError("Unknown family in " + api["id"])
    return {"workbookSha256": WORKBOOK_SHA256, "families": 28, "apis": 129, "roles": counts}


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT), indent=2))
