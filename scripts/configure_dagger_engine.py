#!/usr/bin/env python3

import json
import os
from pathlib import Path
import sys
import tempfile


def configuration_home() -> Path:
    if configured := os.environ.get("XDG_CONFIG_HOME"):
        return Path(configured)
    if sys.platform == "darwin":
        return Path.home() / "Library/Application Support"
    return Path.home() / ".config"


def configure_engine(config_home: Path) -> Path:
    policy_path = Path(__file__).with_name("dagger-engine.json")
    policy = json.loads(policy_path.read_text())
    destination = config_home / "dagger/engine.json"
    existing = json.loads(destination.read_text()) if destination.exists() else {}
    if not isinstance(existing, dict) or not isinstance(existing.get("gc", {}), dict):
        raise ValueError("existing settings and gc must be JSON objects")
    updated = {**existing, "gc": {**existing.get("gc", {}), **policy["gc"]}}
    if updated != existing:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="w", dir=destination.parent, delete=False, encoding="utf-8"
        ) as temporary:
            temporary_path = Path(temporary.name)
            try:
                json.dump(updated, temporary, indent=2)
                temporary.write("\n")
                temporary.flush()
                temporary_path.replace(destination)
            finally:
                temporary_path.unlink(missing_ok=True)
    return destination


if __name__ == "__main__":
    try:
        destination = configure_engine(configuration_home())
    except (OSError, ValueError) as error:
        sys.exit(f"Dagger engine configuration failed: {error}")
    print(f"Dagger engine configuration ready: {destination} (new engines only)")
