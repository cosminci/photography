#!/usr/bin/env python3

from contextlib import ExitStack, closing
from dataclasses import dataclass
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
from typing import Callable, Mapping
import urllib.error
import urllib.request
from uuid import UUID


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
    lock: Path


def make_local_state(project_root: Path) -> LocalState:
    directory = project_root / ".local"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return LocalState(
        directory=directory,
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


def restore_checkpoint(checkpoint: Path, destination: Path) -> None:
    thumbnails = destination / "thumbnails"
    thumbnails.mkdir()
    root = "mnt/user/appdata/photography/"
    database = destination / "photography.db"
    has_thumbnails = False
    with gzip.open(checkpoint, "rb") as compressed, tarfile.open(fileobj=compressed, mode="r|") as archive:
        for member in archive:
            path = member.name.lstrip("/")
            if not path.startswith(root):
                continue
            directory, _, filename = path[len(root) :].partition("/")
            if directory == "thumbnails":
                has_thumbnails = True
                if not filename and member.isdir():
                    continue
                if filename in ("", ".", "..") or "/" in filename:
                    raise StartupError(f"Unexpected NAS thumbnail path: {member.name!r}")
                target = thumbnails / filename
            elif directory == "data" and filename in ("photography.db", "photography.db-wal", "photography.db-shm"):
                target = destination / filename
            else:
                continue
            if not member.isfile() or target.exists():
                raise StartupError(f"Invalid or duplicate NAS checkpoint entry: {member.name!r}")
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
        # Tar iteration ends before gzip checks the trailer.
        while compressed.read(1024 * 1024):
            pass
    if not database.is_file() or not has_thumbnails:
        raise StartupError("NAS checkpoint does not contain both the database and thumbnails")
    with closing(sqlite3.connect(f"{database.as_uri()}?mode=ro", uri=True)) as connection:
        if connection.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise StartupError("NAS checkpoint failed SQLite integrity checks")
        connection.execute("SELECT 1 FROM album LIMIT 1").fetchone()
        for location, version, byte_count in connection.execute("SELECT thumbnail, thumbnail_version, thumbnail_bytes FROM photo"):
            try:
                filename = f"{UUID(location)}.jpg"
            except ValueError as error:
                raise StartupError(f"Invalid thumbnail location in NAS checkpoint: {location!r}") from error
            with (thumbnails / filename).open("rb") as thumbnail:
                content = thumbnail.read(300001)
            if len(content) != byte_count or hashlib.sha256(content).hexdigest() != version:
                raise StartupError(f"NAS checkpoint thumbnail does not match the database: {filename}")


def seed_from_nas(destination: Path, environment: Mapping[str, str]) -> None:
    host = environment.get("PHOTOGRAPHY_NAS_HOST", "root@192.168.1.3")
    backup_root = shlex.quote(environment.get("PHOTOGRAPHY_NAS_BACKUPS", "/mnt/user/cache-backup"))
    command = f"""cd {backup_root} || exit 1
printf '%s\\n' ab_????????_?????? ab_????????_??????-failed | LC_ALL=C sort -r |
while IFS= read -r backup; do
    archive="$backup/album-layout-studio.tar.gz"
    if test -f "$archive" && awk '
        /\\[album-layout-studio\\] Stopping album-layout-studio\\.\\.\\..* done!/ {{ stopped = 1 }}
        /\\[album-layout-studio\\] Verification ended without issues/ && stopped {{ verified = 1 }}
        END {{ exit !verified }}
    ' "$backup/backup.log" 2>/dev/null; then
        printf 'Seeding from %s\\n' "$archive" >&2
        exec cat -- "$archive"
    fi
done
"""
    checkpoint = destination / "checkpoint.tar.gz"
    try:
        with checkpoint.open("wb") as output:
            subprocess.run(
                ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "--", host, command],
                stdout=output,
                check=True,
                timeout=300,
            )
        if checkpoint.stat().st_size == 0:
            raise StartupError("No verified Album Layout Studio checkpoint found on the NAS")
        restore_checkpoint(checkpoint, destination)
    except (OSError, EOFError, subprocess.SubprocessError, tarfile.TarError, sqlite3.Error) as error:
        raise StartupError(f"Could not restore NAS checkpoint: {error}") from error
    finally:
        checkpoint.unlink(missing_ok=True)


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
    seed_lifetime = ExitStack()
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
        seed = Path(seed_lifetime.enter_context(tempfile.TemporaryDirectory(prefix="nas-seed-", dir=state.directory)))
        seed_from_nas(seed, launch_environment)
        launch_environment["PHOTOGRAPHY_SQLITE_PATH"] = str(seed / "photography.db")
        launch_environment["PHOTOGRAPHY_THUMBNAIL_ROOT"] = str(seed / "thumbnails")
        launch_environment.setdefault("PHOTOGRAPHY_PHOTO_ROOT", "/Volumes/media/photos/Travel")
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
                try:
                    seed_lifetime.close()
                finally:
                    lock_file.close()


def terminate(_signum, _frame):
    raise SystemExit(128 + signal.SIGTERM)


def main() -> int:
    project_root = Path(__file__).resolve().parent.parent
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        return run_local_backend(project_root)
    except (PreflightError, StartupError, OSError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    raise SystemExit(main())
