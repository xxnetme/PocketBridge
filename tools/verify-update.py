#!/usr/bin/env python3
"""Install two compatible APKs on a disposable Android device and verify a saved setting."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from release import PACKAGE, ReleaseError, compare, inspect_apk

DUMP = "/data/local/tmp/pocketbridge-upgrade-ui.xml"


def adb(serial: str, *args: str | Path, timeout: int = 45) -> str:
    result = subprocess.run(("adb", "-s", serial, *args), capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise ReleaseError((result.stdout + "\n" + result.stderr).strip())
    return result.stdout.strip()


def language_view(xml: str, version: str) -> tuple[str, int, int]:
    nodes = list(ET.fromstring(xml).iter("node"))
    if not any(n.get("text", "").endswith(" · v" + version) for n in nodes):
        raise ReleaseError(f"PocketBridge v{version} is not visible")
    choices = [n for n in nodes if n.get("text") in ("EN", "RU") and n.get("clickable") == "true"]
    if len(choices) != 1:
        raise ReleaseError("Cannot identify the PocketBridge language button")
    node = choices[0]
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not bounds:
        raise ReleaseError("Invalid bounds for the language button")
    left, top, right, bottom = map(int, bounds.groups())
    if left >= right or top >= bottom:
        raise ReleaseError("The language button is not on screen")
    return node.attrib["text"], (left + right) // 2, (top + bottom) // 2


def wait_for_language(serial: str, version: str, expected: str | None = None) -> tuple[str, int, int]:
    until = time.monotonic() + 40
    last = "No UI dump"
    while time.monotonic() < until:
        try:
            adb(serial, "shell", "rm", "-f", DUMP)
            adb(serial, "shell", "uiautomator", "dump", DUMP, timeout=15)
            view = language_view(adb(serial, "exec-out", "cat", DUMP), version)
            if expected is None or view[0] == expected:
                return view
            last = f"Language button still shows {view[0]}, expected {expected}"
        except (ReleaseError, ET.ParseError, subprocess.TimeoutExpired) as exc:
            last = str(exc)
        time.sleep(1)
    raise ReleaseError(last)


def check_update(serial: str, previous: Path, candidate: Path) -> None:
    old, new = inspect_apk(previous), inspect_apk(candidate)
    compare(old, new)
    if f"package:{PACKAGE}" in adb(serial, "shell", "pm", "list", "packages", PACKAGE).splitlines():
        raise ReleaseError("This device already has PocketBridge. Use a clean, disposable emulator; no app was removed.")

    def launch() -> None:
        answer = adb(serial, "shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        if "Error:" in answer or "Status: ok" not in answer:
            raise ReleaseError("Could not launch PocketBridge: " + answer)

    print(f"Installing {old.version_name} ({old.version_code})", flush=True)
    if "Success" not in adb(serial, "install", "--no-streaming", previous, timeout=180):
        raise ReleaseError("Could not install the previous APK")
    launch()
    language, x, y = wait_for_language(serial, old.version_name)
    expected = "RU" if language == "EN" else "EN"
    adb(serial, "shell", "input", "tap", str(x), str(y))
    wait_for_language(serial, old.version_name, expected)
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    launch()
    wait_for_language(serial, old.version_name, expected)

    # Android Package Manager itself enforces the signing certificate and keeps app data here.
    print(f"Updating to {new.version_name} ({new.version_code}) without uninstall", flush=True)
    if "Success" not in adb(serial, "install", "--no-streaming", "-r", candidate, timeout=180):
        raise ReleaseError("Package Manager rejected the in-place update")
    launch()
    wait_for_language(serial, new.version_name, expected)
    print(f"In-place update {old.version_name} -> {new.version_name} passed: saved language {expected} retained")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="A clean Android test device or emulator")
    parser.add_argument("previous", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    try:
        check_update(args.serial, args.previous.resolve(), args.candidate.resolve())
    except (ReleaseError, OSError, subprocess.TimeoutExpired) as exc:
        parser.exit(1, f"Update smoke check failed: {exc}\n")


if __name__ == "__main__":
    main()
