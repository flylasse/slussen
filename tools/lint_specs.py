#!/usr/bin/env python3
"""Run the AIUP Marketplace spec linter against slussen/docs.

Usage:
    python tools/lint_specs.py            # errors fail, warnings reported
    python tools/lint_specs.py --strict   # warnings fail too
    python tools/lint_specs.py --trace    # traceability matrix

Any extra argument is passed through to spec_lint.py. The linter is run from
the repository's read-only snapshot of the Marketplace,
vendor/aiup-marketplace/ (its commit is recorded in its README.md), so its
sibling validators (validate_use_case.py, bpmn_paths.py) are found there. Set
SLUSSEN_MARKETPLACE to the root of another Marketplace checkout to run that
one's linter instead.
"""
import os
import subprocess
import sys

LINTER_PATH = os.path.join("aiup-core", "skills", "spec-review", "scripts", "spec_lint.py")


def find_linter(repo_root):
    """$SLUSSEN_MARKETPLACE when it is set, otherwise vendor/aiup-marketplace."""
    root = os.environ.get("SLUSSEN_MARKETPLACE") or os.path.join(repo_root, "vendor", "aiup-marketplace")
    linter = os.path.join(root, LINTER_PATH)
    if not os.path.isfile(linter):
        print("spec_lint.py not found: " + linter, flush=True)
        sys.exit(2)
    return linter


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    marketplace_linter = find_linter(repo_root)
    docs_dir = os.path.join(repo_root, "docs")
    cmd = [sys.executable, marketplace_linter, "--docs", docs_dir] + sys.argv[1:]
    print("Running: " + " ".join(cmd), flush=True)
    # The snapshot is read-only: no __pycache__ is written into it.
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    sys.exit(subprocess.run(cmd, env=env).returncode)


if __name__ == "__main__":
    main()
