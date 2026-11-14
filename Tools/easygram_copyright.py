#!/usr/bin/env python3
# This file is part of easygram, a fork of Telegram for Android.
# It is licensed under GNU GPL v. 2 or later.
# You should have received a copy of the license in this archive (see LICENSE).
#
# Copyright webarmour, 2026.

"""
easygram copyright housekeeping, compared with upstream Telegram (git merge-base of HEAD and upstream/master,
uncommitted and new untracked files included):

1. Files added by easygram get the easygram copyright header if they don't have it yet (Java, Kotlin, C/C++,
   Gradle, Python, shell, YAML, XML). Files git sees as copies of Telegram files are NOT marked automatically:
   they need a "Based on ... Copyright Nikolai Kudashov ..." header written by hand (see UpdateLayout.java).
2. NOTICE is regenerated: the list of Telegram files modified by easygram (files with the easygram header,
   i.e. easygram's own files, are left out).

Usage (from anywhere in the repository, before a commit / release):
    python3 Tools/easygram_copyright.py            # add missing headers, rewrite NOTICE
    python3 Tools/easygram_copyright.py --check    # change nothing, exit 1 if something is missing (for CI)
    python3 Tools/easygram_copyright.py --upstream upstream/master   # other upstream ref
"""

import argparse
import datetime
import os
import re
import shutil
import subprocess
import tempfile
import sys

HEADER = """easygram
========

easygram is a modified version of Telegram for Android
(https://github.com/DrKLO/Telegram), Copyright Nikolai Kudashov and
Telegram for Android contributors, licensed under the GNU General Public
License v. 2 or later (see LICENSE).

Modifications and additions: Copyright webarmour, 2026.
They are distributed under the same license (GNU GPL v. 2 or later).

The full history of every change, with dates, is in the git history of
this repository (https://github.com/webarmour/Easygram).


Files added by easygram
-----------------------
Files that are not part of Telegram for Android carry an easygram copyright
header ("This file is part of easygram ..."). Images and data files that
cannot hold a comment (PNG icons and previews, updates/*.json) are
easygram's own as well.

TMessagesProj/src/main/java/org/telegram/ui/Components/UpdateLayout.java is
based on Telegram's UpdateLayout (TMessagesProj_AppStandalone) and keeps
Telegram's copyright next to easygram's.


Telegram for Android files modified by easygram
-----------------------------------------------
These files are Telegram's code with changes made by easygram. To keep
merges with upstream simple, the changes are not marked inside the files;
`git diff` against upstream/master shows them exactly.

"""

EASYGRAM_MARK = "This file is part of easygram"

HEADER_LINES = [
    "This file is part of easygram, a fork of Telegram for Android.",
    "It is licensed under GNU GPL v. 2 or later.",
    "You should have received a copy of the license in this archive (see LICENSE).",
    "",
    "Copyright webarmour, {year}.",
]

BLOCK_COMMENT = {"java", "kt", "kts", "gradle", "c", "cc", "cpp", "h", "hpp"}
HASH_COMMENT = {"py", "sh", "yml", "yaml"}
XML_COMMENT = {"xml"}

# third-party code that easygram may vendor: never claim it
SKIP_PREFIXES = ("TMessagesProj/jni/", "TMessagesProj/libs/", "TMessagesProj_Modules/")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def has_easygram_header(path):
    try:
        with open(path, "rb") as f:
            return EASYGRAM_MARK.encode() in f.read(1024)
    except OSError:
        return False


def header_for(path, year):
    lines = [l.format(year=year) for l in HEADER_LINES]
    ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
    if ext in BLOCK_COMMENT:
        return "/*\n" + "".join((" * " + l).rstrip() + "\n" for l in lines) + " */\n\n"
    if ext in HASH_COMMENT:
        return "".join(("# " + l).rstrip() + "\n" for l in lines) + "\n"
    if ext in XML_COMMENT:
        return "<!--\n" + "".join(("    " + l).rstrip() + "\n" for l in lines) + "-->\n"
    return None


def add_header(path, year):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    header = header_for(path, year)
    if path.lower().endswith(".xml"):
        m = re.match(r"\s*<\?xml[^>]*\?>[ \t]*\n?", text)
        if m:
            text = text[:m.end()].rstrip("\n") + "\n" + header + text[m.end():]
        else:
            text = header + text
    elif text.startswith("#!"):
        i = text.index("\n") + 1
        text = text[:i] + header + text[i:]
    else:
        text = header + text
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def added_files(base):
    """
    (own, copies): files added since base, including new untracked ones. Files that are (even edited) copies of
    any Telegram file are reported separately. A temporary index is used so the real one is never touched.
    """
    untracked = [p for p in git("ls-files", "--others", "--exclude-standard").splitlines() if p]
    fd, tmp_index = tempfile.mkstemp(prefix="easygram-index-")
    os.close(fd)
    try:
        shutil.copyfile(git("rev-parse", "--git-path", "index").strip(), tmp_index)
        env = dict(os.environ, GIT_INDEX_FILE=tmp_index)
        if untracked:
            subprocess.run(["git", "add", "-N", "--", *untracked], check=True, env=env, capture_output=True)
        # --find-copies-harder: copies of unmodified Telegram files too; the limit lets git compare with all files
        out = subprocess.run(["git", "-c", "diff.renameLimit=50000", "diff", "--name-status", "-M", "-C",
                              "--find-copies-harder", "--diff-filter=AC", base],
                             check=True, env=env, capture_output=True, text=True).stdout
    finally:
        os.remove(tmp_index)
    own, copies = [], []
    for line in out.splitlines():
        parts = line.split("\t")
        (copies if parts[0].startswith("C") else own).append(parts[-1])
    return sorted(set(own)), sorted(set(copies))


def needs_header(path):
    return (header_for(path, 0) is not None and not path.startswith(SKIP_PREFIXES)
            and os.path.isfile(path) and not has_easygram_header(path))


def modified_upstream_files(base):
    # working tree vs base: committed + uncommitted changes. M = changed, R = moved/renamed Telegram file.
    out = git("diff", "--name-status", "-M", "--diff-filter=MR", base)
    files = set()
    for line in out.splitlines():
        parts = line.split("\t")
        path = parts[-1]  # for renames: the new path
        if not has_easygram_header(path):
            files.add(path)
    return sorted(files)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="only check, exit 1 if NOTICE is out of date")
    parser.add_argument("--upstream", default="upstream/master", help="upstream ref (default: upstream/master)")
    args = parser.parse_args()

    root = git("rev-parse", "--show-toplevel").strip()
    os.chdir(root)

    base = git("merge-base", "HEAD", args.upstream).strip()
    problems = 0

    own, copies = added_files(base)
    missing = [p for p in own if needs_header(p)]
    unmarked_copies = [p for p in copies if needs_header(p)]
    if missing:
        if args.check:
            print("Files without the easygram copyright header:", file=sys.stderr)
            for p in missing:
                print("  " + p, file=sys.stderr)
            problems += 1
        else:
            year = datetime.date.today().year
            for p in missing:
                add_header(p, year)
                print("header added: " + p)
    if unmarked_copies:
        # copied Telegram code: an easygram-only header would claim someone else's work
        print("Copies of Telegram files, add a 'Based on ... Copyright Nikolai Kudashov ...' header by hand:",
              file=sys.stderr)
        for p in unmarked_copies:
            print("  " + p, file=sys.stderr)
        problems += 1

    files = modified_upstream_files(base)
    text = HEADER + "".join(f"  {path}\n" for path in files)

    try:
        with open("NOTICE", encoding="utf-8") as f:
            current = f.read()
    except FileNotFoundError:
        current = None

    if args.check:
        if current != text:
            print("NOTICE is out of date", file=sys.stderr)
            problems += 1
        if problems:
            print("run: python3 Tools/easygram_copyright.py", file=sys.stderr)
            return 1
        print(f"OK: headers in place, NOTICE up to date ({len(files)} modified Telegram files)")
        return 0

    if current == text:
        print(f"NOTICE already up to date ({len(files)} modified Telegram files)")
    else:
        with open("NOTICE", "w", encoding="utf-8") as f:
            f.write(text)
        print(f"NOTICE updated ({len(files)} modified Telegram files)")
    return 1 if unmarked_copies else 0


if __name__ == "__main__":
    sys.exit(main())
