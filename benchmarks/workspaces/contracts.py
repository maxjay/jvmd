"""The prepared harness consumes the same measurement contract and vectors as TypeScript."""
import json
from pathlib import Path

CONTRACT = json.loads((Path(__file__).parents[1] / "measurement-contract.json").read_text())


def diagnostic_decision(mode, params, uri, version, incarnation=1, event_incarnation=None):
    if params.get("uri") != uri:
        return "ignore", mode
    if event_incarnation is not None and event_incarnation != incarnation:
        return "ignore", mode
    if type(params.get("version")) is int and params["version"] == version:
        if incarnation > 1 and event_incarnation is None:
            return "unavailable", mode
        return "verified", "versioned"
    if "version" not in params and mode != "versioned":
        return "unavailable", "versionless"
    return "ignore", mode
