#!/usr/bin/env python3
"""Reject physical line wraps in Markdown prose paragraphs and list items."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
LIST_ITEM = re.compile(r"^ {0,3}(?:[-+*]|\d+[.)])\s+")


def is_structural(line: str) -> bool:
    stripped = line.lstrip()
    return (
        stripped.startswith(("#", "|", ">", "<!--"))
        or LIST_ITEM.match(line) is not None
        or re.match(r"^(?:[-*_]\s*){3,}$", stripped) is not None
    )


def check(path: Path) -> list[tuple[int, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    errors: list[tuple[int, str]] = []
    in_fence: str | None = None
    in_comment = False
    in_frontmatter = bool(lines and lines[0] == "---")
    previous_prose = 0

    for number, line in enumerate(lines, start=1):
        if in_frontmatter:
            if number > 1 and line == "---":
                in_frontmatter = False
            continue
        fence = FENCE.match(line)
        if fence:
            marker = fence.group(1)
            if in_fence is None:
                in_fence = marker[0]
            elif marker[0] == in_fence:
                in_fence = None
            previous_prose = 0
            continue
        if in_fence is not None:
            continue
        if in_comment:
            if "-->" in line:
                in_comment = False
            previous_prose = 0
            continue
        if "<!--" in line and "-->" not in line:
            in_comment = True
            previous_prose = 0
            continue
        if not line.strip():
            previous_prose = 0
            continue
        if is_structural(line):
            previous_prose = 0
            if LIST_ITEM.match(line) and number < len(lines):
                following = lines[number].rstrip()
                indentation = len(following) - len(following.lstrip())
                if (
                    following.strip()
                    and 1 <= indentation < 4
                    and FENCE.match(following) is None
                    and not is_structural(following)
                ):
                    errors.append((number + 1, f"list item on line {number} continues here"))
            continue
        if previous_prose:
            errors.append((number, f"prose continues physical line {previous_prose}"))
        previous_prose = number

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for path in args.files:
        for line, message in check(path):
            print(f"{path}:{line}: {message}", file=sys.stderr)
            failed = True
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
