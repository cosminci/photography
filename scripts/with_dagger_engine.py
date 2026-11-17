import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import uuid


NAME = "photography-verification"
VOLUME = f"{NAME}-state"
LABEL = "io.cosminci.photography=bounded-dagger"
INVOCATION_LABEL = "io.cosminci.dagger-invocation"
BOOTSTRAP = """
set -eu
size_gib=$1
shift
if [ ! -e /state/engine.ext4 ]; then
  truncate -s "${size_gib}G" /state/engine.ext4
  mkfs.ext4 -q -F -m 0 /state/engine.ext4
fi
test "$(stat -c %s /state/engine.ext4)" = "$((size_gib * 1024 * 1024 * 1024))"
mkdir -p /var/lib/dagger
mount -t ext4 -o loop /state/engine.ext4 /var/lib/dagger
exec /usr/local/bin/dagger-entrypoint.sh "$@"
"""


def docker(*arguments, **options):
    return subprocess.run(["docker", *arguments], text=True, **options)


def start(version, invocation, size_gib):
    cpus = int(docker("info", "--format", "{{.NCPU}}", check=True, stdout=subprocess.PIPE).stdout)
    if cpus < 1:
        raise RuntimeError("Docker reported no available CPUs.")
    docker("volume", "create", "--label", LABEL, VOLUME, check=True, stdout=subprocess.PIPE)
    owner = docker(
        "volume", "inspect", "--format", '{{index .Labels "io.cosminci.photography"}}',
        VOLUME, check=True, stdout=subprocess.PIPE,
    ).stdout.strip()
    if owner != "bounded-dagger":
        raise RuntimeError(f"Refusing a volume not owned by bounded-dagger: {VOLUME}")
    docker(
        "create", "--name", NAME, "--privileged", f"--cpus={min(4, cpus)}", "--memory=6g",
        "--label", f"{INVOCATION_LABEL}={invocation}",
        "--log-driver=local", "--log-opt=max-size=10m", "--log-opt=max-file=2",
        "--mount", f"type=volume,source={VOLUME},target=/state",
        "--entrypoint", "sh", f"registry.dagger.io/engine:{version}",
        "-c", BOOTSTRAP, "bounded-dagger", str(size_gib),
        "--oci-worker-snapshotter=overlayfs",
        f"--oci-worker-gc-keepstorage=2048,2048,{(size_gib - 4) * 1024}",
        check=True, stdout=subprocess.PIPE,
    )


def wait():
    try:
        docker("start", NAME, check=True, stdout=subprocess.PIPE)
        docker(
            "exec", NAME, "timeout", "60", "sh", "-c",
            "until test -S /run/dagger/engine.sock; do sleep 0.1; done",
            check=True,
        )
    except subprocess.CalledProcessError:
        docker("logs", "--tail", "100", NAME, stderr=sys.stderr, stdout=sys.stderr)
        raise


def stop(invocation):
    containers = docker(
        "ps", "-a", "--filter", f"label={INVOCATION_LABEL}={invocation}",
        "--format", "{{.Names}}", stdout=subprocess.PIPE,
    )
    if containers.returncode:
        print("Failed to inspect owned Dagger resources; bounded state was retained.", file=sys.stderr)
        return False
    if NAME not in containers.stdout.splitlines():
        return True
    if docker("rm", "--force", "--volumes", NAME, stdout=subprocess.PIPE).returncode:
        print(f"Failed to remove {NAME}; its bounded state volume was retained.", file=sys.stderr)
        return False
    return docker("volume", "rm", VOLUME, stdout=subprocess.PIPE).returncode == 0


def execute(command):
    environment = {**os.environ, "_EXPERIMENTAL_DAGGER_RUNNER_HOST": f"docker-container://{NAME}"}
    process = subprocess.Popen(command, env=environment, start_new_session=True)
    try:
        return process.wait()
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def run(command, version):
    size_gib = min(20, shutil.disk_usage(Path.cwd())[2] // 1024**3 - 5)
    if size_gib < 8:
        raise RuntimeError("Dagger verification requires at least 13 GiB of host free space.")
    invocation = uuid.uuid4().hex
    result = 1
    try:
        start(version, invocation, size_gib)
        wait()
        result = execute(command)
        if result:
            docker("logs", "--tail", "100", NAME, stderr=sys.stderr, stdout=sys.stderr)
    finally:
        if not stop(invocation) and result == 0:
            result = 1
    return result


def interrupt(signum, _frame):
    raise KeyboardInterrupt(f"Received signal {signum}")


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/with_dagger_engine.py <command> [arguments...]", file=sys.stderr)
        return 2
    signal.signal(signal.SIGTERM, interrupt)
    try:
        version = json.loads((Path(__file__).parents[1] / "dagger.json").read_text())["engineVersion"]
        return run(sys.argv[1:], version)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"dagger-engine: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
