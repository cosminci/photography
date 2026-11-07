#!/usr/bin/env python3

from dataclasses import dataclass
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
from typing import Callable, Mapping
import urllib.error
import urllib.request


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080
DEFAULT_FRONTEND_PORT = 5173
READINESS_TIMEOUT_SECONDS = 180
PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 10


class PreflightError(RuntimeError):
    pass


class AlreadyRunning(PreflightError):
    pass


class StartupError(RuntimeError):
    pass


@dataclass(frozen=True)
class LocalState:
    directory: Path
    database: Path
    lock: Path


def make_local_state(project_root: Path) -> LocalState:
    directory = project_root / ".local"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return LocalState(
        directory=directory,
        database=directory / "photography.db",
        lock=directory / "photography.lock",
    )


def acquire_instance_lock(lock_path: Path):
    lock_file = lock_path.open("a+", encoding="utf-8")
    try:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as error:
        lock_file.close()
        raise AlreadyRunning("Another local photography backend is already running") from error
    return lock_file


def is_port_available(host: str, port: int) -> bool:
    try:
        addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return False
    if not addresses:
        return False

    family, socket_type, protocol, _, address = addresses[0]
    with socket.socket(family, socket_type, protocol) as listener:
        try:
            listener.bind(address)
        except OSError:
            return False
    return True


def check_prerequisites(host: str, port: int, frontend_port: int | None = None) -> None:
    for executable in ("java", "sbt"):
        if shutil.which(executable) is None:
            raise PreflightError(f"Required executable '{executable}' was not found on PATH")
    if not is_port_available(host, port):
        raise PreflightError(f"Port {port} is unavailable on {host}")
    if frontend_port is not None:
        if shutil.which("npm") is None:
            raise PreflightError("Required executable 'npm' was not found on PATH")
        if frontend_port == port or not is_port_available(host, frontend_port):
            raise PreflightError(f"Frontend port {frontend_port} is unavailable on {host}")


def build_service_url(host: str, port: int) -> str:
    display_host = {"0.0.0.0": "127.0.0.1", "::": "::1"}.get(host, host)
    if ":" in display_host and not display_host.startswith("["):
        display_host = f"[{display_host}]"
    return f"http://{display_host}:{port}"


def build_health_url(host: str, port: int) -> str:
    return f"{build_service_url(host, port)}/health"


def is_backend_ready(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            content_type = response.headers.get_content_type()
            payload = json.loads(response.read().decode("utf-8"))
            return response.status == 200 and content_type == "application/json" and payload == {"status": "ok"}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ConnectionError):
        return False


def is_frontend_ready(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200 and response.headers.get_content_type() == "text/html"
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return False


def wait_for_readiness(
    process: subprocess.Popen[bytes],
    url: str,
    timeout: int = READINESS_TIMEOUT_SECONDS,
    readiness_check: Callable[[str], bool] | None = None,
    service_name: str = "Backend",
) -> None:
    check = readiness_check or is_backend_ready
    deadline = time.monotonic() + timeout
    while True:
        exit_code = process.poll()
        if exit_code is not None:
            raise StartupError(f"{service_name} exited before readiness with status {exit_code}")
        if check(url):
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"{service_name} did not become ready within {timeout} seconds")
        time.sleep(min(1, remaining))


def stop_process_tree(process: subprocess.Popen[bytes]) -> None:
    process.poll()
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait()
        return
    deadline = time.monotonic() + PROCESS_SHUTDOWN_TIMEOUT_SECONDS
    while True:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            break
        if time.monotonic() >= deadline:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            break
        time.sleep(0.1)
    process.wait()


def parse_port(environment: Mapping[str, str]) -> int:
    raw_port = environment.get("PHOTOGRAPHY_PORT")
    if raw_port is None:
        return DEFAULT_PORT
    try:
        port = int(raw_port)
    except ValueError as error:
        raise PreflightError("PHOTOGRAPHY_PORT must be an integer in 1..65535") from error
    if port < 1 or port > 65535:
        raise PreflightError("PHOTOGRAPHY_PORT must be an integer in 1..65535")
    return port


def parse_frontend_port(environment: Mapping[str, str]) -> int:
    raw_port = environment.get("PHOTOGRAPHY_FRONTEND_PORT")
    if raw_port is None:
        return DEFAULT_FRONTEND_PORT
    try:
        port = int(raw_port)
    except ValueError as error:
        raise PreflightError("PHOTOGRAPHY_FRONTEND_PORT must be an integer in 1..65535") from error
    if port < 1 or port > 65535:
        raise PreflightError("PHOTOGRAPHY_FRONTEND_PORT must be an integer in 1..65535")
    return port


def wait_for_services(
    backend: subprocess.Popen[bytes],
    frontend: subprocess.Popen[bytes],
) -> int:
    while True:
        backend_exit = backend.poll()
        if backend_exit is not None:
            return backend_exit
        frontend_exit = frontend.poll()
        if frontend_exit is not None:
            raise StartupError(f"Frontend exited with status {frontend_exit}")
        time.sleep(1)


def run_local_backend(project_root: Path, environment: Mapping[str, str] | None = None) -> int:
    launch_environment = dict(os.environ if environment is None else environment)
    state = make_local_state(project_root)
    lock_file = acquire_instance_lock(state.lock)
    process: subprocess.Popen[bytes] | None = None
    frontend_process: subprocess.Popen[bytes] | None = None
    try:
        host = launch_environment.get("PHOTOGRAPHY_HOST") or DEFAULT_HOST
        port = parse_port(launch_environment)
        frontend = project_root / "frontend"
        has_frontend = (frontend / "package.json").is_file()
        frontend_port = parse_frontend_port(launch_environment) if has_frontend else None
        check_prerequisites(host, port, frontend_port)
        if has_frontend:
            subprocess.run(["npm", "ci"], cwd=frontend, env=launch_environment, check=True)
            subprocess.run(["npm", "run", "build"], cwd=frontend, env=launch_environment, check=True)
        launch_environment.setdefault("PHOTOGRAPHY_SQLITE_PATH", str(state.database))
        process = subprocess.Popen(
            ["sbt", "-batch", "run"],
            cwd=project_root / "backend",
            env=launch_environment,
            start_new_session=True,
        )
        endpoint = build_service_url(host, port)
        wait_for_readiness(process, url=f"{endpoint}/health")
        print(f"Photography backend ready at {endpoint}")
        if frontend_port is not None:
            frontend_environment = {**launch_environment, "VITE_API_TARGET": endpoint}
            frontend_process = subprocess.Popen(
                [
                    "npm",
                    "run",
                    "dev",
                    "--",
                    "--host",
                    host,
                    "--port",
                    str(frontend_port),
                    "--strictPort",
                ],
                cwd=frontend,
                env=frontend_environment,
                start_new_session=True,
            )
            frontend_endpoint = build_service_url(host, frontend_port)
            wait_for_readiness(
                frontend_process,
                frontend_endpoint,
                readiness_check=is_frontend_ready,
                service_name="Frontend",
            )
            print(f"Photography frontend ready at {frontend_endpoint}")
            return wait_for_services(process, frontend_process)
        return process.wait()
    finally:
        try:
            if frontend_process is not None:
                stop_process_tree(frontend_process)
        finally:
            try:
                if process is not None:
                    stop_process_tree(process)
            finally:
                lock_file.close()


def main() -> int:
    project_root = Path(__file__).resolve().parent.parent
    try:
        return run_local_backend(project_root)
    except (PreflightError, StartupError, OSError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
