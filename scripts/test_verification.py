import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ZERO = "0" * 40


class VerificationSeamIntegrationTest(unittest.TestCase):
    def test_should_ci_and_push_use_the_same_verification_entrypoint(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        hook = (ROOT / ".githooks/pre-push").read_text()

        self.assertIn('bash scripts/verify "origin/${GITHUB_BASE_REF}"', workflow)
        self.assertIn("scripts/verify", hook)
        self.assertNotIn("dagger call verify", workflow)

    def test_should_run_every_shared_gate_for_documentation_only_changes(self):
        repository, environment, base = self.build_repository()
        (repository / "a document.md").write_text("One complete paragraph.\n")
        head = self.commit(repository)
        expected_commands = [
            "npm ci --prefix .dagger --no-audit --no-fund",
            "dagger develop",
            "npm --prefix .dagger run verify",
            f"dagger call verify --base={base}",
        ]

        result = self.push(repository, environment, head, base)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((repository / "commands").read_text().splitlines(), expected_commands)

    def test_should_block_each_failed_gate_without_running_later_gates(self):
        for failed_command in (
            "npm ci --prefix .dagger --no-audit --no-fund",
            "dagger develop",
            "npm --prefix .dagger run verify",
            "dagger call verify",
        ):
            with self.subTest(failed_command=failed_command):
                repository, environment, base = self.build_repository()
                environment["FAIL_COMMAND"] = failed_command
                head = self.commit(repository)

                result = self.push(repository, environment, head, base)

                self.assertNotEqual(result.returncode, 0, result.stderr)
                commands = (repository / "commands").read_text().splitlines()
                self.assertTrue(commands[-1].startswith(failed_command), commands)

    def test_should_reject_wrapped_markdown_before_building(self):
        repository, environment, base = self.build_repository()
        (repository / "a document.md").write_text("A paragraph\ncontinued here.\n")
        head = self.commit(repository)

        result = self.push(repository, environment, head, base)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("prose continues physical line", result.stderr)
        self.assertFalse((repository / "commands").exists())

    def test_should_check_outgoing_main_against_the_remote_old_commit(self):
        repository, environment, base = self.build_repository()
        (repository / "root.txt").write_text("first change\n")
        first_head = self.commit(repository)
        (repository / "root.txt").write_text("second change\n")
        head = self.commit(repository)
        self.git(repository, "update-ref", "refs/remotes/origin/main", first_head)

        result = self.push(repository, environment, head, base)

        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (repository / "commands").read_text().splitlines()
        self.assertEqual(commands[-1], f"dagger call verify --base={base}")

    def test_should_use_the_target_remotes_main_for_a_feature_branch(self):
        repository, environment, _ = self.build_repository()
        personal_base = self.commit(repository)
        self.git(repository, "update-ref", "refs/remotes/personal/main", personal_base)
        head = self.commit(repository)

        result = self.push(repository, environment, head, ZERO, "feature/test", remote="personal")

        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (repository / "commands").read_text().splitlines()
        self.assertEqual(commands[-1], f"dagger call verify --base={personal_base}")

    def test_should_reject_a_revision_other_than_the_checked_out_head(self):
        repository, environment, base = self.build_repository()
        self.commit(repository)

        result = self.push(repository, environment, base, base)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checked-out HEAD", result.stderr)
        self.assertFalse((repository / "commands").exists())

    def test_should_reject_uncommitted_source_changes(self):
        for source_state in ("tracked", "untracked"):
            with self.subTest(source_state=source_state):
                repository, environment, base = self.build_repository()
                head = self.commit(repository)
                path = ".githooks/pre-push" if source_state == "tracked" else "backend/untracked.scala"
                source_file = repository / path
                source_file.parent.mkdir(parents=True, exist_ok=True)
                source_file.write_text("uncommitted source\n")

                result = self.push(repository, environment, head, base)

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("uncommitted", result.stderr)
                self.assertFalse((repository / "commands").exists())

    def test_should_allow_deletion_without_building(self):
        repository, environment, base = self.build_repository()

        result = self.push(repository, environment, ZERO, base)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((repository / "commands").exists())

    def test_should_allow_ignored_local_evidence_and_deleted_markdown(self):
        repository, environment, _ = self.build_repository()
        (repository / ".gitignore").write_text(".agent-work/\n")
        document = repository / "old document.md"
        document.write_text("A retired\nwrapped paragraph.\n")
        base = self.commit(repository)
        document.unlink()
        head = self.commit(repository)
        evidence = repository / ".agent-work/evidence.txt"
        evidence.parent.mkdir()
        evidence.write_text("Local verification evidence.\n")

        result = self.push(repository, environment, head, base)

        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (repository / "commands").read_text().splitlines()
        self.assertEqual(commands[-1], f"dagger call verify --base={base}")

    def test_should_verify_each_update_without_consuming_the_next_record(self):
        repository, environment, base = self.build_repository()
        (repository / ".gitignore").write_text("commands\n")
        tool = repository / "bin/dagger"
        tool.write_text(tool.read_text() + 'cat >/dev/null\n')
        head = self.commit(repository)
        update = f"refs/heads/main {head} refs/heads/main {base}\n"

        result = subprocess.run(
            ["bash", ".githooks/pre-push", "origin", "unused"],
            input=update + update,
            cwd=repository,
            env=environment,
            text=True,
            capture_output=True,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (repository / "commands").read_text().splitlines()
        self.assertEqual(commands.count(f"dagger call verify --base={base}"), 2)

    def test_should_fail_when_the_verification_base_is_missing_or_invalid(self):
        for arguments in ([], ["missing-ref"]):
            with self.subTest(arguments=arguments):
                repository, environment, _ = self.build_repository()

                result = subprocess.run(
                    ["bash", "scripts/verify", *arguments],
                    cwd=repository,
                    env=environment,
                    text=True,
                    capture_output=True,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertTrue(result.stderr)
                self.assertFalse((repository / "commands").exists())

    def build_repository(self):
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        repository = Path(temporary_directory.name)
        for path in (
            ".githooks/pre-push",
            ".agents/skills/sdd/scripts/check_markdown_wrapping.py",
            "scripts/verify",
        ):
            target = repository / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, target)
        self.git(repository, "init", "-b", "main")
        base = self.commit(repository)
        self.git(repository, "update-ref", "refs/remotes/origin/main", base)
        commands = repository / "bin"
        commands.mkdir()
        tool = (
            "#!/bin/sh\n"
            'command="$(basename "$0") $*"\n'
            'printf "%s\\n" "$command" >> "$COMMAND_LOG"\n'
            'case "$command" in "${FAIL_COMMAND:-never}"*) exit 17 ;; esac\n'
        )
        for name in ("npm", "dagger"):
            executable = commands / name
            executable.write_text(tool)
            executable.chmod(0o755)
        environment = {
            **os.environ,
            "PATH": f"{commands}:/usr/bin:/bin:/usr/local/bin",
            "COMMAND_LOG": str(repository / "commands"),
            "FAIL_COMMAND": "",
            "_EXPERIMENTAL_DAGGER_RUNNER_HOST": "tcp://verification.invalid",
        }
        return repository, environment, base

    def commit(self, repository):
        self.git(repository, "add", ".")
        self.git(repository, "commit", "--allow-empty", "-m", "Verification scenario")
        return self.git(repository, "rev-parse", "HEAD").stdout.strip()

    def git(self, repository, *arguments):
        return subprocess.run(
            [
                "git",
                "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=/dev/null",
                "-c", "user.name=VerificationTest",
                "-c", "user.email=verification@example.invalid",
                *arguments,
            ],
            cwd=repository,
            text=True,
            capture_output=True,
            check=True,
        )

    def push(self, repository, environment, head, base, branch="main", remote="origin"):
        return subprocess.run(
            ["bash", ".githooks/pre-push", remote, "unused"],
            input=f"refs/heads/{branch} {head} refs/heads/{branch} {base}\n",
            cwd=repository,
            env=environment,
            text=True,
            capture_output=True,
        )
