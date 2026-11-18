#!/usr/bin/env python3
#
# This file is part of easygram, a fork of Telegram for Android.
# It is licensed under GNU GPL v. 2 or later.
# You should have received a copy of the license in this archive (see LICENSE).
#
# Copyright webarmour, 2026.
#
"""Builds the mirror of the on-device speech recognition models for the GitHub release asr-models-v1.

The app downloads every model file from
https://github.com/webarmour/Easygram/releases/download/asr-models-v1/<model id>--<file name>
(and falls back to Hugging Face where the file exists there). This script fetches the upstream sherpa-onnx
archives, extracts the files the app needs, checks them against the sizes and SHA-256 hashes written in
EasygramAsrModels.java and puts them, renamed, together with the model licenses into an output directory.

    python3 Tools/asr_models_mirror.py [--out DIR] [--only ID[,ID...]] [--upload]

--only limits the work to some models (e.g. --only lid-whisper-tiny), --upload creates the release (if missing)
and uploads the files with the GitHub CLI (gh).
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "TMessagesProj/src/main/java/org/telegram/messenger/asr/EasygramAsrModels.java")
TAG = "asr-models-v1"
REPO = "webarmour/Easygram"
UPSTREAM = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

HF = "https://huggingface.co/csukuangfj/"

# model id -> upstream sherpa-onnx archive (its top directory has the same name),
# or ("hf", repository, license URL) for models published as separate files on Hugging Face
SOURCES = {
    "lid-whisper-tiny": ("hf", "sherpa-onnx-whisper-tiny", "https://raw.githubusercontent.com/openai/whisper/main/LICENSE"),
    "ru-t-one": "sherpa-onnx-streaming-t-one-russian-2025-09-08",
    "ru-gigaam-v3": "sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16",
    "en-moonshine-tiny": "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
    "en-moonshine-base": "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
    "en-parakeet-v2": "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
}


def read_catalog():
    """Returns {model id: [(file name, size, sha256)]} parsed from EasygramAsrModels.java."""
    text = open(CATALOG, encoding="utf-8").read()
    files = {}
    for m in re.finditer(r'mirrored\("([^"]+)", "([^"]+)", (\d+)L, "([0-9a-f]{64})"', text):
        files.setdefault(m.group(1), []).append((m.group(2), int(m.group(3)), m.group(4)))
    return files


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest):
    if os.path.exists(dest):
        return
    print("downloading", url)
    tmp = dest + ".part"
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as out:
        shutil.copyfileobj(r, out, 1 << 20)
    os.replace(tmp, dest)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=os.path.join(ROOT, "build", "asr-models-mirror"))
    parser.add_argument("--only", default="")
    parser.add_argument("--upload", action="store_true")
    args = parser.parse_args()

    catalog = read_catalog()
    if args.only:
        wanted_ids = set(args.only.split(","))
        unknown = wanted_ids - set(catalog)
        if unknown:
            sys.exit("not in the catalog: " + ", ".join(sorted(unknown)))
        catalog = {k: v for k, v in catalog.items() if k in wanted_ids}
    missing = set(catalog) - set(SOURCES)
    if missing:
        sys.exit("no upstream source for " + ", ".join(sorted(missing)))
    work = os.path.join(args.out, "_archives")
    os.makedirs(work, exist_ok=True)

    outputs = []
    for model_id, entries in catalog.items():
        source = SOURCES[model_id]
        if isinstance(source, tuple):
            _, repo, license_url = source
            for fname, size, digest in entries:
                dest = os.path.join(args.out, f"{model_id}--{fname}")
                download(HF + repo + "/resolve/main/" + fname, dest)
                actual_size, actual = os.path.getsize(dest), sha256(dest)
                if actual_size != size or actual != digest:
                    sys.exit(f"{dest}: size {actual_size} sha256 {actual}, catalog expects {size} {digest}")
                outputs.append(dest)
                print("  ok", os.path.basename(dest))
            dest = os.path.join(args.out, f"{model_id}--LICENSE")
            download(license_url, dest)
            outputs.append(dest)
            print("  ok", os.path.basename(dest))
            continue
        name = source
        archive = os.path.join(work, name + ".tar.bz2")
        download(UPSTREAM + name + ".tar.bz2", archive)
        with tarfile.open(archive, "r:bz2") as tar:
            members = {m.name: m for m in tar.getmembers() if m.isfile()}
            wanted = [(fname, size, digest) for fname, size, digest in entries] + [("LICENSE", None, None)]
            for fname, size, digest in wanted:
                member = members.get(name + "/" + fname)
                if member is None:
                    if fname == "LICENSE":
                        print("  (no LICENSE in", name + ")")
                        continue
                    sys.exit(f"{name}/{fname} is not in the archive")
                dest = os.path.join(args.out, f"{model_id}--{fname}")
                with tar.extractfile(member) as src, open(dest, "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
                if digest is not None:
                    actual_size, actual = os.path.getsize(dest), sha256(dest)
                    if actual_size != size or actual != digest:
                        sys.exit(f"{dest}: size {actual_size} sha256 {actual}, catalog expects {size} {digest}")
                outputs.append(dest)
                print("  ok", os.path.basename(dest))

    print(f"\n{len(outputs)} files in {args.out}")
    if args.upload:
        exists = subprocess.run(["gh", "release", "view", TAG, "-R", REPO], capture_output=True).returncode == 0
        if not exists:
            subprocess.run(["gh", "release", "create", TAG, "-R", REPO, "--title", "Speech recognition models (v1)",
                            "--notes", "Files downloaded by easygram for on-device voice transcription. "
                                       "Each model keeps its own license (see the *--LICENSE files).",
                            "--latest=false"], check=True)
        subprocess.run(["gh", "release", "upload", TAG, "-R", REPO, "--clobber", *outputs], check=True)
    else:
        files = " ".join('"' + o + '"' for o in outputs)
        print(f"upload with: gh release upload {TAG} -R {REPO} --clobber {files}")


if __name__ == "__main__":
    main()
