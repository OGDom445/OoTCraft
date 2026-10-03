"""Fails if the repository (or a built jar) contains game data: ROMs, extracted Ship of Harkinian archives, or any
suspiciously large binary. OoTCraft must never ship Nintendo or Mojang material - players bring their own games."""
import os
import subprocess
import sys
import zipfile

BANNED_EXT = {".z64", ".n64", ".v64", ".rom", ".o2r", ".otr", ".nds", ".iso", ".wad", ".ndd", ".bin"}
N64_MAGIC = {bytes.fromhex(m) for m in ("80371240", "37804012", "40123780")}  # big, byte-swapped, little endian
MAX_BYTES = 2 * 1024 * 1024
ALLOWED_LARGE = set()


def check_blob(name, data, problems):
    ext = os.path.splitext(name)[1].lower()
    if ext in BANNED_EXT:
        problems.append(f"{name}: game data file type ({ext}) is not allowed")
    if data[:4] in N64_MAGIC:
        problems.append(f"{name}: looks like a Nintendo 64 ROM")
    if len(data) > MAX_BYTES and name not in ALLOWED_LARGE:
        problems.append(f"{name}: {len(data) // 1024} KB is too large for this repository")


def main():
    problems = []
    files = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout.decode().split("\0")
    for name in filter(None, files):
        with open(name, "rb") as f:
            check_blob(name, f.read(), problems)
    for jar in sys.argv[1:]:
        with zipfile.ZipFile(jar) as z:
            for info in z.infolist():
                check_blob(f"{jar}!{info.filename}", z.read(info.filename), problems)
    if problems:
        print("Game data or oversized files found. OoTCraft never includes ROMs or game assets:")
        print("\n".join("  - " + p for p in problems))
        sys.exit(1)
    print(f"OK: no ROMs or game data ({len(files) - 1} files checked, {len(sys.argv) - 1} jar(s)).")


if __name__ == "__main__":
    main()
