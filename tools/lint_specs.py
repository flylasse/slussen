#!/usr/bin/env python3
"""Run the AIUP spec_lint against slussen/docs."""
import sys
import subprocess
import os

MARKETPLACE_LINTER = r"C:\work\marketplace\aiup-core\skills\spec-review\scripts\spec_lint.py"

def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    docs_dir = os.path.join(repo_root, "docs")
    
    cmd = [sys.executable, MARKETPLACE_LINTER, "--docs", docs_dir, "--strict"] + sys.argv[1:]
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd)
    sys.exit(result.returncode)

if __name__ == "__main__":
    main()
