#!/usr/bin/env python3
"""Name APKs from AGP metadata and create checksums without accessing signing material."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("ci", "release"))
    parser.add_argument("--tag")
    args = parser.parse_args()
    if args.mode == "release" and not args.tag:
        parser.error("Release packaging requires --tag.")

    sha = subprocess.check_output(["git", "rev-parse", "--short=7", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        raise SystemExit("Cannot identify the source commit.")
    variants = ("preview", "debug") if args.mode == "ci" else ("release",)
    version = None
    outputs = {"commit": sha}
    for variant in variants:
        directory = Path("app/build/outputs/apk") / variant
        metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        expected_id = "dev.streetsnap.app.ci" if args.mode == "ci" else "dev.streetsnap.app"
        if metadata.get("applicationId") != expected_id:
            raise SystemExit(f"Unexpected application ID for {variant}; check -PciBuild and signing configuration.")
        elements = metadata.get("elements", [])
        if len(elements) != 1 or elements[0].get("filters"):
            raise SystemExit("Expected one universal APK, without split APK filters.")
        element = elements[0]
        current_version = element.get("versionName", "")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z][0-9A-Za-z.-]*)?", current_version):
            raise SystemExit("Unsupported versionName format.")
        if version is not None and version != current_version:
            raise SystemExit("The APK variants have different versions.")
        version = current_version
        if args.mode == "release" and args.tag != f"v{version}":
            raise SystemExit("The release tag must equal v followed by the APK versionName.")
        source_name = element.get("outputFile", "")
        if not source_name or Path(source_name).name != source_name:
            raise SystemExit("Invalid APK filename in output metadata.")
        source = directory / source_name
        if not source.is_file() or source.suffix != ".apk":
            raise SystemExit("The declared APK does not exist.")
        name = f"street-snap-v{version}"
        if args.mode == "ci":
            name += f"-{variant}-ci-{sha}"
        destination = Path("dist") / variant
        destination.mkdir(parents=True, exist_ok=True)
        apk = destination / f"{name}.apk"
        shutil.copyfile(source, apk)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (destination / "SHA256SUMS").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
        outputs[f"{variant}_artifact"] = name
        outputs[f"{variant}_file"] = apk.name
        outputs[f"{variant}_sha256"] = digest
        print(f"Prepared {apk.name} ({apk.stat().st_size:,} bytes)")
    outputs["version"] = version
    if output_path := os.environ.get("GITHUB_OUTPUT"):
        with open(output_path, "a", encoding="utf-8") as output:
            for name, value in outputs.items():
                output.write(f"{name}={value}\n")


if __name__ == "__main__":
    main()
