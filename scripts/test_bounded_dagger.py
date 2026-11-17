import signal
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import with_dagger_engine as engine


class BoundedDaggerComponentTest(unittest.TestCase):
    def setUp(self):
        process_patch = patch.object(engine.subprocess, "Popen")
        self.process_factory = process_patch.start()
        self.addCleanup(process_patch.stop)
        self.process = self.process_factory.return_value
        self.process.pid = 314159
        self.process.wait.return_value = 0
        kill_patch = patch.object(engine.os, "killpg")
        self.kill = kill_patch.start()
        self.addCleanup(kill_patch.stop)
        space = patch.object(engine.shutil, "disk_usage", return_value=(0, 0, 30 * 1024**3))
        space.start()
        self.addCleanup(space.stop)

    def test_should_bound_active_storage_and_clean_up_successful_or_failed_commands(self):
        for status in (0, 17):
            with self.subTest(status=status):
                calls = []
                self.process.wait.return_value = status

                with patch.object(engine.subprocess, "run", side_effect=self.build_docker(calls)):
                    result = engine.run(["dagger", "call", "verify"], "v0.21.11")

                creation = next(command for command in calls if command[:2] == ["docker", "create"])
                self.assertEqual(result, status)
                self.assertIn(f"type=volume,source={engine.VOLUME},target=/state", creation)
                self.assertIn('truncate -s "${size_gib}G"', creation[creation.index("-c") + 1])
                self.assertIn("mount -t ext4 -o loop", creation[creation.index("-c") + 1])
                self.assertEqual(creation[-3:], ["20", "--oci-worker-snapshotter=overlayfs", "--oci-worker-gc-keepstorage=2048,2048,16384"])
                self.assertEqual(calls[-2:], [
                    ["docker", "rm", "--force", "--volumes", engine.NAME],
                    ["docker", "volume", "rm", engine.VOLUME],
                ])
                self.assertTrue(self.process_factory.call_args.kwargs["start_new_session"])
                self.assertEqual(self.process_factory.call_args.kwargs["env"]["_EXPERIMENTAL_DAGGER_RUNNER_HOST"], f"docker-container://{engine.NAME}")

    def test_should_fit_the_state_disk_to_host_capacity_and_reserve_free_space(self):
        calls = []

        with patch.object(engine.subprocess, "run", side_effect=self.build_docker(calls)):
            with patch.object(engine.shutil, "disk_usage", return_value=(0, 0, 15 * 1024**3)):
                result = engine.run(["dagger", "call", "verify"], "v0.21.11")

        creation = next(command for command in calls if command[:2] == ["docker", "create"])
        self.assertEqual(result, 0)
        self.assertEqual(creation[-3], "10")

    def test_should_reject_insufficient_host_space_without_starting_docker(self):
        with patch.object(engine.subprocess, "run") as docker:
            with patch.object(engine.shutil, "disk_usage", return_value=(0, 0, 12 * 1024**3)):
                with self.assertRaisesRegex(RuntimeError, "13 GiB"):
                    engine.run(["dagger", "call", "verify"], "v0.21.11")

        docker.assert_not_called()

    def test_should_not_remove_a_conflicting_engine(self):
        calls = []

        with patch.object(engine.subprocess, "run", side_effect=self.build_docker(calls, conflict=True)):
            with self.assertRaises(subprocess.CalledProcessError):
                engine.run(["dagger", "call", "verify"], "v0.21.11")

        self.assertFalse(any(command[:2] == ["docker", "rm"] for command in calls))

    def test_should_clean_up_an_interrupted_creation_by_its_invocation_label(self):
        calls = []

        with patch.object(engine.subprocess, "run", side_effect=self.build_docker(calls, interrupt=True)):
            with self.assertRaises(KeyboardInterrupt):
                engine.run(["dagger", "call", "verify"], "v0.21.11")

        self.assertEqual(calls[-1], ["docker", "volume", "rm", engine.VOLUME])

    def test_should_kill_the_owned_process_group_on_cancellation(self):
        self.process.wait.side_effect = [KeyboardInterrupt, -signal.SIGKILL]

        with self.assertRaises(KeyboardInterrupt):
            engine.execute(["dagger", "call", "verify"])

        self.kill.assert_called_once_with(self.process.pid, signal.SIGKILL)
        self.assertEqual(self.process.wait.call_count, 2)

    def test_should_bound_every_workflow_that_calls_dagger_directly(self):
        workflows = Path(__file__).parents[1] / ".github/workflows"
        shell = "shell: python3 scripts/with_dagger_engine.py bash --noprofile --norc -euo pipefail {0}"
        for name in ("release.yml", "verify-main.yml"):
            with self.subTest(workflow=name):
                source = (workflows / name).read_text()

                self.assertIn(shell, source)
                self.assertLess(source.index("sudo rm -rf -- /usr/local/lib/android"), source.index("dagger develop"))

    def test_should_fit_cpu_limits_to_two_cpu_hosted_runners(self):
        calls = []

        with patch.object(engine.subprocess, "run", side_effect=self.build_docker(calls, cpus=2)):
            result = engine.run(["dagger", "call", "verify"], "v0.21.11")

        creation = next(command for command in calls if command[:2] == ["docker", "create"])
        self.assertEqual(result, 0)
        self.assertIn("--cpus=2", creation)

    def build_docker(self, calls, conflict=False, interrupt=False, cpus=4):
        created = False

        def execute(command, **options):
            nonlocal created
            calls.append(command)
            if command[1] == "create":
                if conflict:
                    raise subprocess.CalledProcessError(1, command)
                created = True
                if interrupt:
                    raise KeyboardInterrupt
            if command[1] == "info":
                output = str(cpus)
            else:
                output = f"{engine.NAME}\n" if command[1] == "ps" and created else "bounded-dagger\n"
            return subprocess.CompletedProcess(command, 0, stdout=output)

        return execute
