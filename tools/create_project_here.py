#!/usr/bin/env python3
"""Copy this generated starter project into the current directory."""
from pathlib import Path
import shutil
import sys

SRC = Path(__file__).resolve().parents[1]
DST = Path.cwd()
SKIP = {".git", "build", ".gradle"}

for item in SRC.iterdir():
    if item.name in SKIP or item.name == "tools":
        continue
    target = DST / item.name
    if item.is_dir():
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(item, target)
    else:
        shutil.copy2(item, target)
print(f"Copied UTCOMP Kotlin probe project files to {DST}")
