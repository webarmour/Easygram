#!/usr/bin/env python3
"""Prepare a signed APK update feed entry for Easygram."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {
    "beta": "io.github.webarmour.easygram.beta",
    "stable": "io.github.webarmour.easygram",
    "web": "io.github.webarmour.easygram.web",
}


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = ROOT / "local.properties"
        for line in local.read_text().splitlines() if local.exists() else []:
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1]
                break
    if not sdk:
        raise ValueError("Set ANDROID_HOME or sdk.dir in local.properties")
    candidates = list((Path(sdk) / "build-tools").glob("*/" + name))
    if not candidates:
        raise ValueError(f"Android build tool {name} not found in {sdk}")
    return max(candidates, key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def apk_info(apk, aapt, apksigner):
    badging = run(str(aapt), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError(f"Could not read package metadata from {apk}")
    certificates = run(str(apksigner), "verify", "--print-certs", str(apk))
    signers = re.findall(r"^(?:Signer #\d+|V\d+ Signer): certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certificates, re.M)
    if not signers:
        raise ValueError(f"No verified signing certificate in {apk}")
    return match.group(1), int(match.group(2)), match.group(3), sorted(set(signers))


def sha256(apk):
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_url(url, expected_hash):
    digest = hashlib.sha256()
    total = 0
    with urlopen(url, timeout=30) as response:
        if urlparse(response.url).scheme != "https":
            raise ValueError("APK URL redirected away from HTTPS")
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            total += len(chunk)
            if total > 300 * 1024 * 1024:
                raise ValueError("Remote APK is larger than the updater limit")
            digest.update(chunk)
    if digest.hexdigest() != expected_hash:
        raise ValueError("APK at the public URL differs from the local APK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("channel", choices=PACKAGES)
    parser.add_argument("apk", type=Path)
    parser.add_argument("apk_url", help="Public HTTPS URL of this exact APK")
    parser.add_argument("--changelog", required=True)
    parser.add_argument("--previous-apk", type=Path, help="Compare the signing certificate with the installed version")
    parser.add_argument("--output", type=Path, help="Defaults to updates/<channel>.json")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print the feed without writing it")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    url = urlparse(args.apk_url)
    if url.scheme != "https" or not url.netloc or url.username or url.password:
        parser.error("apk_url must be a public HTTPS URL without credentials")

    aapt, apksigner = build_tool("aapt"), build_tool("apksigner")
    package, version_code, version_name, signers = apk_info(args.apk, aapt, apksigner)
    if package != PACKAGES[args.channel]:
        parser.error(f"{args.channel} requires {PACKAGES[args.channel]}, APK contains {package}")
    if args.previous_apk:
        if not args.previous_apk.is_file():
            parser.error(f"Previous APK not found: {args.previous_apk}")
        previous_package, previous_code, _, previous_signers = apk_info(args.previous_apk, aapt, apksigner)
        if previous_package != package or previous_code >= version_code or previous_signers != signers:
            parser.error("Previous APK must have the same package and signer, and a lower version code")

    output = args.output or ROOT / "updates" / f"{args.channel}.json"
    if output.exists():
        old = json.loads(output.read_text(encoding="utf-8"))
        if old.get("package_name") != package:
            parser.error(f"Existing feed has another package: {output}")
        if version_code <= old.get("version_code", 0):
            parser.error(f"APK version code must exceed the current feed ({old['version_code']})")

    feed = {
        "version_code": version_code,
        "version_name": version_name,
        "package_name": package,
        "apk_url": args.apk_url,
        "sha256": sha256(args.apk),
        "changelog": args.changelog,
    }
    serialized = json.dumps(feed, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(serialized, end="")
    else:
        verify_url(args.apk_url, feed["sha256"])
        output.write_text(serialized, encoding="utf-8")
        print(f"Wrote {output}")


if __name__ == "__main__":
    main()
