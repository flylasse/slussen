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

MARKETPLACE_LINTER = r"C:\work\marketplace\aiup-core\skills\spec-review\scripts\spec_lint.py"


def main():
    if not os.path.isfile(MARKETPLACE_LINTER):
        print(f"spec_lint.py not found at {MARKETPLACE_LINTER}", flush=True)
        sys.exit(2)
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    docs_dir = os.path.join(repo_root, "docs")
    cmd = [sys.executable, MARKETPLACE_LINTER, "--docs", docs_dir] + sys.argv[1:]
    print("Running: " + " ".join(cmd), flush=True)
    sys.exit(subprocess.run(cmd).returncode)


if __name__ == "__main__":
    main()
