#!/usr/bin/env python3
"""Run the AIUP Marketplace spec linter against slussen/docs.

Usage:
    python tools/lint_specs.py            # errors fail, warnings reported
    python tools/lint_specs.py --strict   # warnings fail too
    python tools/lint_specs.py --trace    # traceability matrix

Any extra argument is passed through to spec_lint.py. The linter is run from
the local Marketplace clone (pinned commit recorded in the bootstrap plan), so
its sibling validators (validate_use_case.py, bpmn_paths.py) are found.
"""
import os
import subprocess
import sys

LINTER_PATH = os.path.join("aiup-core", "skills", "spec-review", "scripts", "spec_lint.py")


def find_linter(repo_root):
    """The Marketplace clone: $SLUSSEN_MARKETPLACE, C:\\work\\marketplace, or ../marketplace."""
    roots = [os.environ.get("SLUSSEN_MARKETPLACE"), r"C:\work\marketplace",
             os.path.join(os.path.dirname(repo_root), "marketplace")]
    candidates = [os.path.join(r, LINTER_PATH) for r in roots if r]
    for c in candidates:
        if os.path.isfile(c):
            return c
    print("spec_lint.py not found; looked in: " + ", ".join(candidates), flush=True)
    sys.exit(2)


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    MARKETPLACE_LINTER = find_linter(repo_root)
    docs_dir = os.path.join(repo_root, "docs")
    cmd = [sys.executable, MARKETPLACE_LINTER, "--docs", docs_dir] + sys.argv[1:]
    print("Running: " + " ".join(cmd), flush=True)
    sys.exit(subprocess.run(cmd).returncode)


if __name__ == "__main__":
    main()
