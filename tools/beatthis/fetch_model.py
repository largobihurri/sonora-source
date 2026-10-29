#!/usr/bin/env python3
"""Download the Beat This! ONNX model described by model.json into build/ and verify its sha256.

Standard library only. Usage:

    python3 fetch_model.py            # model only
    python3 fetch_model.py --refs    # also the reference mel filterbank, the reference front end and the upstream checkpoint (used by make_goldens.py and validate.py)

The files are pinned by revision in the URL and by sha256; a mismatch deletes the download and exits with status 1.
"""
import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUILD = HERE / "build"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(entry: dict) -> Path:
    target = BUILD / entry["file"]
    if target.exists() and sha256_of(target) == entry["sha256"]:
        print(f"ok (cached)  {target.name}  {entry['sha256']}")
        return target
    BUILD.mkdir(exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    print(f"downloading  {entry['url']}")
    with urllib.request.urlopen(entry["url"], timeout=60) as response, partial.open("wb") as out:
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            out.write(block)
    actual = sha256_of(partial)
    if actual != entry["sha256"] or partial.stat().st_size != entry["sizeBytes"]:
        partial.unlink()
        sys.exit(f"FAILED {target.name}: sha256 {actual} (expected {entry['sha256']}), "
                 f"size must be {entry['sizeBytes']}")
    partial.replace(target)
    print(f"ok           {target.name}  {actual}")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--refs", action="store_true", help="also fetch the reference filterbank, front end and upstream checkpoint")
    args = parser.parse_args()
    manifest = json.loads((HERE / "model.json").read_text())
    fetch(manifest)
    if args.refs:
        fetch(manifest["melFilterbankReference"])
        fetch(manifest["melFrontEndReference"])
        fetch(manifest["checkpointReference"])


if __name__ == "__main__":
    main()
