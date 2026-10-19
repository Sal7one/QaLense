#!/usr/bin/env python3
"""Write a small, synthetic .sal for local-model workflow checks. No captured app data."""
import argparse
import copy
import gzip
import json
from pathlib import Path
import zipfile
import zlib

FIXTURE = Path(__file__).with_name("recording.json")


def write_archive(path, fixture=None):
    source = copy.deepcopy(fixture) if fixture is not None else json.loads(FIXTURE.read_text())
    manifest = source.pop("manifest")
    entries, metadata = {}, []
    for track, value in source.items():
        name = track + ".json"
        decoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        entries[name] = gzip.compress(decoded, mtime=0)
        metadata.append({"name": name, "crc32": f"{zlib.crc32(decoded) & 0xffffffff:08x}", "compressed": True})
    manifest["files"] = metadata
    entries["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
    # STORE outside gzip is portable to Node 18; the production reader still verifies
    # both ZIP CRCs and the manifest's decoded-content CRCs.
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return Path(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output path; this fixture does not overwrite existing recordings.")
    write_archive(args.output)
    print(f"Synthetic text-only recording: {args.output}")


if __name__ == "__main__":
    main()
