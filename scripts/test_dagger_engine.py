import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from configure_dagger_engine import configuration_home


ROOT = Path(__file__).resolve().parents[1]


class DaggerEngineConfigurationTest(unittest.TestCase):
    def test_should_match_daggers_native_config_location_without_an_xdg_override(self):
        for platform, relative in (
            ("darwin", "Library/Application Support"),
            ("linux", ".config"),
        ):
            for configured in (None, ""):
                with self.subTest(platform=platform, configured=configured):
                    environment = {**os.environ, "HOME": "/tmp/dagger-config-test"}
                    environment.pop("XDG_CONFIG_HOME", None)
                    if configured is not None:
                        environment["XDG_CONFIG_HOME"] = configured
                    with patch.dict(os.environ, environment, clear=True), patch("sys.platform", platform):
                        self.assertEqual(
                            configuration_home(),
                            Path("/tmp/dagger-config-test") / relative,
                        )

    def test_should_install_bounded_age_and_size_policies_before_starting_an_engine(self):
        with tempfile.TemporaryDirectory() as temporary:
            config_home = Path(temporary)

            result = self.configure(config_home)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                json.loads((config_home / "dagger/engine.json").read_text()),
                {
                    "gc": {
                        "enabled": True,
                        "policies": [
                            {"all": True, "keepDuration": "72h"},
                            {
                                "all": True,
                                "reservedSpace": "2GB",
                                "maxUsedSpace": "20GB",
                                "minFreeSpace": "50GB",
                                "sweepSize": "5GB",
                            },
                        ],
                    }
                },
            )

    def test_should_preserve_unrelated_settings_and_replace_unbounded_retention(self):
        with tempfile.TemporaryDirectory() as temporary:
            config_home = Path(temporary)
            destination = config_home / "dagger/engine.json"
            destination.parent.mkdir()
            destination.write_text(json.dumps({"logLevel": "warn", "gc": {"enabled": False}}))

            result = self.configure(config_home)

            self.assertEqual(result.returncode, 0, result.stderr)
            installed = json.loads(destination.read_text())
            expected = json.loads((ROOT / "scripts/dagger-engine.json").read_text())
            self.assertEqual(installed, {"logLevel": "warn", **expected})

    def test_should_leave_an_already_installed_policy_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            config_home = Path(temporary)
            self.assertEqual(self.configure(config_home).returncode, 0)
            destination = config_home / "dagger/engine.json"
            before = destination.stat().st_mtime_ns

            result = self.configure(config_home)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(destination.stat().st_mtime_ns, before)

    def test_should_reject_invalid_existing_settings_without_overwriting_them(self):
        for contents in ("{", "[]", '{"gc": false}'):
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as temporary:
                config_home = Path(temporary)
                destination = config_home / "dagger/engine.json"
                destination.parent.mkdir()
                destination.write_text(contents)

                result = self.configure(config_home)

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Dagger engine configuration", result.stderr)
                self.assertEqual(destination.read_text(), contents)

    def test_should_install_retention_before_verification_or_publication(self):
        verification = (ROOT / "scripts/verify").read_text()
        release = (ROOT / ".github/workflows/release.yml").read_text()
        setup = "python3 scripts/configure_dagger_engine.py"

        self.assertLess(verification.index(setup), verification.index("dagger develop"))
        self.assertLess(release.index(setup), release.index("dagger develop"))

    def configure(self, config_home):
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts/configure_dagger_engine.py")],
            env={**os.environ, "XDG_CONFIG_HOME": str(config_home)},
            capture_output=True,
            text=True,
        )
