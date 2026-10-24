#!/usr/bin/env python3
"""Exercise installed Travis through its UI and Android GPS service, on an emulator only.

Creates a QA outing and virtual-camera photo; preserves existing outings. Coordinates
are injected test stimuli, never physical GPS evidence. Model inference is not tested.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--pickup", action="store_true", help="Also exercise an opted-in leave/return reminder through Android notifications")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This script injects simulated GPS and must never target a physical phone.")
bundled = ROOT / ".tooling/android-sdk/platform-tools/adb"
ADB = os.environ.get("TRAVIS_ADB") or (str(bundled) if bundled.is_file() else shutil.which("adb"))
if not ADB:
    parser.error("Set TRAVIS_ADB or install Android platform-tools.")
OUTPUT = ROOT / "local-data/gps-integration" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
OUTPUT.mkdir(parents=True)
report = {"hardware": "Android emulator", "serial": args.serial,
          "location_inputs": "Injected via adb emu geo fix", "camera": "Emulator virtual scene",
          "physical_phone_test": False, "model_inference_test": False, "checks": []}


def adb(*parts, binary=False):
    result = subprocess.run([ADB, "-s", args.serial, *parts], capture_output=True,
                            text=not binary, timeout=30)
    if result.returncode:
        raise RuntimeError(f"ADB failed: {parts}: {result.stderr}")
    return result.stdout


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/travis-ui.xml")
    data = adb("exec-out", "cat", "/sdcard/travis-ui.xml")
    (OUTPUT / "last-ui.xml").write_text(data)
    return ET.fromstring(data)


def node(label, tree):
    return next((n for n in tree.iter("node") if label in (n.get("text"), n.get("content-desc"))), None)


def tap(label):
    tree = ui()
    target = node(label, tree)
    if target is None or target.get("enabled") != "true":
        raise RuntimeError(f"Enabled control not found: {label}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.3)


def outings():
    # run-as works before the first outing file exists; distinguish absence from corrupt data.
    listing = adb("exec-out", "run-as", "com.backtrail.app", "ls", "-1", "files")
    if "outings.json" not in listing.splitlines():
        return []
    return json.loads(adb("exec-out", "run-as", "com.backtrail.app", "cat", "files/outings.json"))


def active():
    return next(x for x in outings() if x["id"] == report["outing_id"])


def wait_for(predicate, description, seconds=15):
    deadline = time.monotonic()+seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.4)
    raise AssertionError(description)


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    report["checks"].append(name)
    print("PASS:", name, flush=True)


def move(lon):
    adb("emu", "geo", "fix", str(lon), "37.56")
    wait_for(lambda: active()["points"] and abs(active()["points"][-1]["longitude"]-lon)<.000005,
             "Injected point did not reach persistent route")


def pickup_notification(memory_id):
    # Mirror Java String.hashCode only to locate this app's chosen OS notification id.
    unsigned = 0
    for char in memory_id:
        unsigned = (31 * unsigned + ord(char)) & 0xffffffff
    notification_id = unsigned if unsigned < 0x80000000 else unsigned - 0x100000000
    dump = adb("shell", "dumpsys", "notification", "--noredact")
    for block in dump.split("NotificationRecord(")[1:]:
        header = block.splitlines()[0]
        if "pkg=com.backtrail.app " not in header or f" id={notification_id} " not in header:
            continue
        updated = re.search(r"mUpdateTimeMs=(\d+)", block)
        title = re.search(r"android.title=String \(([^\n]*)\)", block)
        if updated and title:
            return {"id": notification_id, "updated_at_ms": int(updated.group(1)), "title": title.group(1)}
    return None


try:
    adb("shell", "am", "force-stop", "com.backtrail.app")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "cmd", "location", "set-location-enabled", "true")
    for permission in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "CAMERA", "POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", "com.backtrail.app", "android.permission."+permission)
    adb("shell", "am", "start", "-W", "-n", "com.backtrail.app/.MainActivity", "--ez", "explore", "false")
    before = outings()
    (OUTPUT / "before.json").write_text(json.dumps(before, indent=2))
    tree = ui()
    if node("End", tree) is not None:
        tap("End")
    tree = ui()
    tap("New outing" if node("New outing", tree) is not None else "Start outing")
    previous_ids = {x["id"] for x in before}
    wait_for(lambda: any(x["id"] not in previous_ids for x in outings()), "New outing was not persisted")
    report["outing_id"] = next(x["id"] for x in outings() if x["id"] not in previous_ids)
    move(-122.3600)
    move(-122.3598)
    first_segment = active()["points"][-1]["segment"]
    check("UI-started recording persists measured provider callbacks", len(active()["points"]) >= 2)

    adb("shell", "cmd", "location", "set-location-enabled", "false")
    time.sleep(1)
    # API 36 may show a system-wide lost-device warning above the app.
    tree = ui()
    if node("No location access", tree) is not None and node("Close", tree) is not None:
        tap("Close")
    check("Disabled GPS is visible", node("GPS is disabled. Recording has a gap.", ui()) is not None)
    adb("shell", "cmd", "location", "set-location-enabled", "true")
    move(-122.3590)
    check("Brief GPS outage starts a new segment", active()["points"][-1]["segment"] > first_segment)
    move(-122.3588)

    tap("Pause recording")
    wait_for(lambda: active()["phase"] == "PAUSED", "Pause was not persisted")
    paused_points = active()["points"]
    adb("emu", "geo", "fix", "-122.3580", "37.56")
    time.sleep(2)
    check("Pause leaves the recorded route unchanged", active()["points"] == paused_points)
    tap("Resume outing")
    move(-122.3578)
    check("Resume preserves the recording gap", active()["points"][-1]["segment"] > paused_points[-1]["segment"])

    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    move(-122.3573)
    check("Foreground recording continues with emulator screen asleep", active()["points"][-1]["longitude"] > -122.3574)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    tap("Save a place")
    if args.pickup:
        tap("Remind me on the way back")
    # Virtual camera capture is a real CameraX path but is not a physical photograph.
    tap("Save photo & place")
    wait_for(lambda: len(active()["memories"]) == 1, "CameraX photo metadata was not persisted", seconds=25)
    memory = active()["memories"][0]
    photo = adb("exec-out", "run-as", "com.backtrail.app", "cat", memory["photoPath"], binary=True)
    check("Original virtual-camera JPEG and location persist", photo[:2] == b"\xff\xd8" and memory["point"] is not None)

    if args.pickup:
        memory_id = memory["id"]
        def pickup_phase():
            return next(m["pickup"] for m in active()["memories"] if m["id"] == memory_id)
        time.sleep(4)
        check("Pickup does not fire at capture location", pickup_phase() == "WAITING_TO_LEAVE" and pickup_notification(memory_id) is None)
        move(-122.3558)
        wait_for(lambda: pickup_phase() == "ARMED", "Leaving did not arm the requested pickup", seconds=20)
        check("Leaving arms the requested pickup without notifying", pickup_notification(memory_id) is None)
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        move(-122.3573)
        wait_for(lambda: pickup_phase() == "NOTIFIED", "Return did not persist the notification state", seconds=20)
        wait_for(lambda: pickup_notification(memory_id) is not None, "Android did not receive the pickup notification")
        posted = pickup_notification(memory_id)
        check("Android receives pickup notification with screen asleep", posted["title"].startswith("Pickup nearby"))
        report["pickup_notification"] = posted
        time.sleep(6)
        check("More arrival fixes do not repeat the pickup notification", pickup_notification(memory_id) == posted)
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")

    tap("Head back")
    wait_for(lambda: active()["retraceIndex"] is not None, "Retrace cursor was not persisted")
    cursor = active()["retraceIndex"]
    tap("Saved places")
    tap("Find in AR")
    check("Find preserves the retrace cursor", active()["retraceIndex"] == cursor)
    tree = ui()
    check("Find displays the original saved landmark", node("Original saved landmark", tree) is not None)
    check("Find clears the previous retrace notice", node("Following the recorded route in reverse order.", tree) is None)
    (OUTPUT / "find-ar.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    tap("Map")
    check("Map switch preserves retrace cursor", active()["retraceIndex"] == cursor)
    tap("Resume retrace")
    check("Resume returns to retrace guidance", node("RETRACING YOUR STEPS", ui()) is not None)
    check("Resume restores the same target index", active()["retraceIndex"] == cursor)
    tap("Close guidance")
    services = adb("shell", "dumpsys", "activity", "services", "com.backtrail.app")
    check("Closing ended-outing guidance stops recording service", "ServiceRecord" not in services)
    saved = active()
    adb("shell", "am", "force-stop", "com.backtrail.app")
    adb("shell", "am", "start", "-n", "com.backtrail.app/.MainActivity")
    time.sleep(1)
    check("Process relaunch preserves route and original photo metadata", active() == saved)
    (OUTPUT / "after.json").write_text(json.dumps(outings(), indent=2))
    report["passed"] = True
except Exception as error:
    report["passed"] = False
    report["error"] = str(error)
    raise
finally:
    report["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    (OUTPUT / "receipt.json").write_text(json.dumps(report, indent=2)+"\n")
    print("Receipt:", OUTPUT / "receipt.json", flush=True)
