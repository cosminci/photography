import signal
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import CalledProcessError
from unittest import mock

import local


class LocalStateComponentTest(unittest.TestCase):
    def test_should_state_directory_be_private_and_use_a_local_database(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            state = local.make_local_state(Path(temporary_directory))

            self.assertEqual(state.directory, Path(temporary_directory) / ".local")
            self.assertEqual(state.database, state.directory / "photography.db")
            self.assertEqual(stat.S_IMODE(state.directory.stat().st_mode), 0o700)

    def test_should_instance_lock_reject_a_second_launcher_and_release_after_close(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            state = local.make_local_state(Path(temporary_directory))
            first_lock = local.acquire_instance_lock(state.lock)

            try:
                with self.assertRaises(local.AlreadyRunning):
                    local.acquire_instance_lock(state.lock)
            finally:
                first_lock.close()

            second_lock = local.acquire_instance_lock(state.lock)
            second_lock.close()


class LocalRunnerComponentTest(unittest.TestCase):
    def test_should_health_url_use_loopback_for_wildcard_bind_addresses(self):
        ipv4_url = local.build_health_url(host="0.0.0.0", port=8080)
        ipv6_url = local.build_health_url(host="::", port=8080)
        local_url = local.build_health_url(host="127.0.0.1", port=8080)

        self.assertEqual(ipv4_url, "http://127.0.0.1:8080/health")
        self.assertEqual(ipv6_url, "http://[::1]:8080/health")
        self.assertEqual(local_url, "http://127.0.0.1:8080/health")

    def test_should_preflight_check_required_tools_and_port_availability(self):
        with mock.patch.object(local.shutil, "which", side_effect=["/usr/bin/java", "/usr/bin/sbt"]):
            with mock.patch.object(local, "is_port_available", return_value=True):
                local.check_prerequisites(host="127.0.0.1", port=8080)

        with mock.patch.object(local.shutil, "which", return_value=None):
            with self.assertRaises(local.PreflightError):
                local.check_prerequisites(host="127.0.0.1", port=8080)

        with mock.patch.object(
            local.shutil,
            "which",
            side_effect=["/usr/bin/java", None],
        ):
            with self.assertRaises(local.PreflightError):
                local.check_prerequisites(host="127.0.0.1", port=8080)

        with mock.patch.object(local.shutil, "which", return_value="/usr/bin/tool"):
            with mock.patch.object(local, "is_port_available", return_value=False):
                with self.assertRaises(local.PreflightError):
                    local.check_prerequisites(host="127.0.0.1", port=8080)

    def test_should_readiness_wait_for_the_exact_health_response(self):
        process = mock.Mock()
        process.poll.return_value = None

        with mock.patch.object(local, "is_backend_ready", side_effect=[False, True]):
            with mock.patch.object(local.time, "sleep"):
                local.wait_for_readiness(process, url="http://127.0.0.1:8080/health")

        process.poll.assert_called()

    def test_should_page_check_require_successful_html(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.headers.get_content_type.return_value = "text/html"

        with mock.patch.object(local.urllib.request, "urlopen", return_value=response):
            self.assertTrue(local.is_frontend_ready(url="http://127.0.0.1:5173"))

        response.status = 503
        with mock.patch.object(local.urllib.request, "urlopen", return_value=response):
            self.assertFalse(local.is_frontend_ready(url="http://127.0.0.1:5173"))

    def test_should_readiness_fail_if_process_exits_or_deadline_expires(self):
        exited_process = mock.Mock()
        exited_process.poll.return_value = 7

        with self.assertRaises(local.StartupError):
            local.wait_for_readiness(exited_process, url="http://127.0.0.1:8080/health")

        running_process = mock.Mock()
        running_process.poll.return_value = None
        with mock.patch.object(local.time, "monotonic", side_effect=[0, 181]):
            with mock.patch.object(local, "is_backend_ready", return_value=False):
                with self.assertRaises(TimeoutError):
                    local.wait_for_readiness(
                        running_process,
                        url="http://127.0.0.1:8080/health",
                        timeout=180,
                    )

    def test_should_shutdown_terminate_the_process_group_and_escalate_if_needed(self):
        process = mock.Mock()
        process.pid = 42
        process.poll.return_value = None
        process.wait.return_value = 0

        with mock.patch.object(local.os, "killpg", side_effect=[None, ProcessLookupError]) as kill_process_group:
            local.stop_process_tree(process)

        self.assertEqual(kill_process_group.call_args_list, [mock.call(42, signal.SIGTERM), mock.call(42, 0)])

        stuck_process = mock.Mock()
        stuck_process.pid = 43
        stuck_process.poll.return_value = None
        with (
            mock.patch.object(local.os, "killpg") as kill_process_group,
            mock.patch.object(local.time, "monotonic", side_effect=[0, 11]),
        ):
            local.stop_process_tree(stuck_process)

        expected_signals = [mock.call(43, signal.SIGTERM), mock.call(43, 0), mock.call(43, signal.SIGKILL)]

        self.assertEqual(kill_process_group.call_args_list, expected_signals)

    def test_should_stop_surviving_descendants_even_after_the_group_leader_exits(self):
        child = "import os, signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print(os.getpid(), flush=True); time.sleep(60)"
        for leader_exited in (False, True):
            with self.subTest(leader_exited=leader_exited):
                leader = (
                    f"import subprocess, sys, time; subprocess.Popen([sys.executable, '-c', {child!r}]); "
                    + ("sys.exit(0)" if leader_exited else "time.sleep(60)")
                )
                process = subprocess.Popen([sys.executable, "-c", leader], start_new_session=True, stdout=subprocess.PIPE)
                try:
                    self.assertIsNotNone(process.stdout)
                    child_pid = int(process.stdout.readline())
                    self.assertNotEqual(child_pid, process.pid)
                    if leader_exited:
                        process.wait(timeout=5)

                    with mock.patch.object(local, "PROCESS_SHUTDOWN_TIMEOUT_SECONDS", 0.2):
                        local.stop_process_tree(process)

                    output, _ = process.communicate(timeout=5)
                    self.assertEqual(output, b"")
                    self.assertIsNotNone(process.returncode)
                finally:
                    try:
                        local.os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.communicate(timeout=5)

    def test_should_launcher_pass_overrides_to_the_child_and_stop_it(self):
        for database_override in (None, "/isolated/album-year.sqlite"):
            with self.subTest(database_override=database_override), tempfile.TemporaryDirectory() as temporary_directory:
                process = mock.Mock()
                process.pid = 44
                process.poll.return_value = None
                process.wait.return_value = 0
                environment = {"PHOTOGRAPHY_HOST": "0.0.0.0", "PHOTOGRAPHY_PORT": "8090"}
                if database_override is not None:
                    environment["PHOTOGRAPHY_SQLITE_PATH"] = database_override
                with (
                    mock.patch.object(local, "check_prerequisites") as check_prerequisites,
                    mock.patch.object(local.subprocess, "Popen", return_value=process) as start,
                    mock.patch.object(local, "wait_for_readiness") as ready,
                    mock.patch.object(local, "stop_process_tree") as stop,
                    mock.patch("builtins.print") as output,
                ):
                    result = local.run_local_backend(Path(temporary_directory), environment)

                state = local.make_local_state(Path(temporary_directory))
                next_lock = local.acquire_instance_lock(state.lock)
                next_lock.close()
                expected_database = database_override or str(state.database)
                actual_database = start.call_args.kwargs["env"]["PHOTOGRAPHY_SQLITE_PATH"]

                self.assertEqual(result, 0)
                check_prerequisites.assert_called_once_with("0.0.0.0", 8090, None)
                self.assertEqual(start.call_args.args[0], ["sbt", "-batch", "run"])
                self.assertEqual(start.call_args.kwargs["cwd"], Path(temporary_directory) / "backend")
                self.assertTrue(start.call_args.kwargs["start_new_session"])
                self.assertEqual(start.call_args.kwargs["env"]["PHOTOGRAPHY_HOST"], "0.0.0.0")
                self.assertEqual(start.call_args.kwargs["env"]["PHOTOGRAPHY_PORT"], "8090")
                self.assertEqual(actual_database, expected_database)
                ready.assert_called_once_with(process, url="http://127.0.0.1:8090/health")
                stop.assert_called_once_with(process)
                output.assert_called_once_with("Photography backend ready at http://127.0.0.1:8090")

    def test_should_readiness_timeout_stop_the_child_and_release_the_lock(self):
        process = mock.Mock()
        process.pid = 46
        process.poll.return_value = None

        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "check_prerequisites"):
                with mock.patch.object(local.subprocess, "Popen", return_value=process):
                    with mock.patch.object(local, "wait_for_readiness", side_effect=TimeoutError("not ready")):
                        with mock.patch.object(local, "stop_process_tree") as stop:
                            with mock.patch("builtins.print"):
                                with self.assertRaises(TimeoutError):
                                    local.run_local_backend(Path(temporary_directory), environment={})

            stop.assert_called_once_with(process)
            state = local.make_local_state(Path(temporary_directory))
            next_lock = local.acquire_instance_lock(state.lock)
            next_lock.close()

    def test_should_preflight_failure_avoid_starting_the_backend(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "check_prerequisites", side_effect=local.PreflightError("port busy")):
                with mock.patch.object(local.subprocess, "Popen") as start:
                    with self.assertRaises(local.PreflightError):
                        local.run_local_backend(Path(temporary_directory), environment={})

        start.assert_not_called()

    def test_should_start_the_checked_out_frontend_alongside_the_backend(self):
        backend_process = mock.Mock()
        backend_process.pid = 47
        backend_process.poll.return_value = None
        frontend_process = mock.Mock()
        frontend_process.pid = 48
        frontend_process.poll.return_value = None

        for dependencies_present in (False, True):
            with self.subTest(dependencies_present=dependencies_present), tempfile.TemporaryDirectory() as temporary_directory:
                frontend = Path(temporary_directory) / "frontend"
                frontend.mkdir()
                (frontend / "package.json").touch()
                if dependencies_present:
                    (frontend / "node_modules" / ".bin").mkdir(parents=True)
                    (frontend / "node_modules" / ".bin" / "vite").touch()
                environment = {"PHOTOGRAPHY_FRONTEND_PORT": "5174"}
                expected_preparation = [
                    mock.call(["npm", "ci"], cwd=frontend, env=mock.ANY, check=True),
                    mock.call(["npm", "run", "build"], cwd=frontend, env=mock.ANY, check=True),
                ]

                with (
                    mock.patch.object(local, "check_prerequisites") as check_prerequisites,
                    mock.patch.object(local.subprocess, "run") as preparation,
                    mock.patch.object(local.subprocess, "Popen", side_effect=[backend_process, frontend_process]) as start,
                    mock.patch.object(local, "wait_for_readiness") as ready,
                    mock.patch.object(local, "wait_for_services", return_value=0),
                    mock.patch.object(local, "stop_process_tree"),
                    mock.patch("builtins.print"),
                ):
                    result = local.run_local_backend(Path(temporary_directory), environment)

                self.assertEqual(result, 0)
                check_prerequisites.assert_called_once_with("127.0.0.1", 8080, 5174)
                self.assertEqual(preparation.call_args_list, expected_preparation)
                self.assertEqual(preparation.call_args.kwargs["env"]["PHOTOGRAPHY_FRONTEND_PORT"], "5174")
                self.assertEqual(start.call_count, 2)
                self.assertEqual(start.call_args_list[1].args[0][0:3], ["npm", "run", "dev"])
                self.assertEqual(start.call_args_list[1].kwargs["cwd"], frontend)
                vite_target = start.call_args_list[1].kwargs["env"]["VITE_API_TARGET"]
                self.assertEqual(vite_target, "http://127.0.0.1:8080")
                self.assertEqual(ready.call_count, 2)
                self.assertEqual(ready.call_args_list[1].kwargs["service_name"], "Frontend")

    def test_should_preparation_failure_stop_startup_and_release_the_lock(self):
        for command in (["npm", "ci"], ["npm", "run", "build"]):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as temporary_directory:
                root = Path(temporary_directory)
                frontend = root / "frontend"
                frontend.mkdir()
                (frontend / "package.json").touch()
                failure = CalledProcessError(1, command)
                outcomes = [failure] if command == ["npm", "ci"] else [None, failure]

                with (
                    mock.patch.object(local, "check_prerequisites"),
                    mock.patch.object(local.subprocess, "run", side_effect=outcomes),
                    mock.patch.object(local.subprocess, "Popen") as start,
                ):
                    with self.assertRaises(CalledProcessError) as raised:
                        local.run_local_backend(root, {})

                self.assertIs(raised.exception, failure)
                start.assert_not_called()
                next_lock = local.acquire_instance_lock(root / ".local" / "photography.lock")
                next_lock.close()

    def test_should_main_report_preparation_failure_without_starting_services(self):
        failure = CalledProcessError(1, ["npm", "ci"])

        with (
            mock.patch.object(local, "run_local_backend", side_effect=failure),
            mock.patch("builtins.print") as output,
        ):
            result = local.main()

        self.assertEqual(result, 1)
        output.assert_called_once_with(f"Error: {failure}", file=local.sys.stderr)

    def test_should_interruption_stop_the_child_and_release_the_lock(self):
        process = mock.Mock()
        process.pid = 45
        process.poll.return_value = None
        process.wait.side_effect = KeyboardInterrupt()

        with tempfile.TemporaryDirectory() as temporary_directory:
            with mock.patch.object(local, "check_prerequisites"):
                with mock.patch.object(local.subprocess, "Popen", return_value=process):
                    with mock.patch.object(local, "wait_for_readiness"):
                        with mock.patch.object(local, "stop_process_tree") as stop:
                            with mock.patch("builtins.print"):
                                with self.assertRaises(KeyboardInterrupt):
                                    local.run_local_backend(Path(temporary_directory), environment={})

            stop.assert_called_once_with(process)
            state = local.make_local_state(Path(temporary_directory))
            next_lock = local.acquire_instance_lock(state.lock)
            next_lock.close()


if __name__ == "__main__":
    unittest.main()
