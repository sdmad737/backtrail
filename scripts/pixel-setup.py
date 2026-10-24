#!/usr/bin/env python3
"""Install prepared Travis assets on one physical Pixel; never clear app data.

Uses the local SDK when available. No network downloads. Importing the model
through Travis remains a separate, explicit step; installation is not inference.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.backtrail.app"


def run(args, *, capture=True, check=True, timeout=60):
    return subprocess.run(args, text=True, capture_output=capture,
                          check=check, timeout=timeout)


def verify_local(path, expected):
    if not path.is_file() or path.stat().st_size != expected["bytes"]:
        raise RuntimeError(f"Missing/incomplete asset: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(8 * 1024 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != expected["sha256"]:
        raise RuntimeError(f"SHA-256 mismatch: {path}")
    print(f"Verified {path.name}: {expected['bytes']} bytes, {digest.hexdigest()}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Required if multiple physical devices are connected")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--verify-host-only", action="store_true")
    modes.add_argument("--verify-private", action="store_true",
                       help="After in-app import, verify the persistent app-private model")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "docs/model-assets.json").read_text())
    model = next(a for a in manifest["assets"] if a["kind"] == "model_bundle")
    build = json.loads((ROOT / "docs/build-receipt.json").read_text())
    model_path = ROOT / "models" / model["filename"]
    apk_path = ROOT / build["apk_path"]
    verify_local(model_path, model)
    verify_local(apk_path, {"bytes": build["apk_bytes"], "sha256": build["apk_sha256"]})
    if args.verify_host_only:
        print("Host verification complete. No device commands ran.")
        return

    bundled_adb = ROOT / ".tooling/android-sdk/platform-tools/adb"
    adb_path = str(bundled_adb) if bundled_adb.is_file() else shutil.which("adb")
    if not adb_path:
        raise RuntimeError("ADB is missing; install Android platform-tools first.")
    listing = run([adb_path, "devices", "-l"]).stdout
    devices = [line.split() for line in listing.splitlines()[1:] if line.strip()]
    available = [d[0] for d in devices if len(d) >= 2 and d[1] == "device"
                 and not d[0].startswith("emulator-")]
    serial = args.serial or (available[0] if len(available) == 1 else None)
    if not serial or serial not in available:
        print(listing)
        raise RuntimeError("Connect/unlock/authorize one physical Pixel, or pass --serial.")

    def adb(*parts, **kwargs):
        return run([adb_path, "-s", serial, *parts], **kwargs)

    def shell(*parts, **kwargs):
        return adb("shell", shlex.join(parts), **kwargs)

    device = {name: shell("getprop", prop).stdout.strip() for name, prop in {
        "model": "ro.product.model", "android": "ro.build.version.release",
        "sdk": "ro.build.version.sdk", "abi": "ro.product.cpu.abi",
        "soc": "ro.soc.model", "build": "ro.build.id"}.items()}
    if "Pixel" not in device["model"] or device["abi"] != "arm64-v8a":
        raise RuntimeError(f"Expected a physical ARM64 Pixel; observed {device}")
    print(json.dumps(device, indent=2), flush=True)
    receipt = {"started_at_utc": datetime.now(timezone.utc).isoformat(),
               "device": device, "apk_sha256": build["apk_sha256"],
               "model_bytes": model["bytes"], "model_sha256": model["sha256"],
               "apk_installed_this_run": False, "download_copy_verified_this_run": False,
               "app_private_copy_verified_this_run": False,
               "offline_inference_tested_this_run": False}
    directory = ROOT / "local-data/pixel"
    directory.mkdir(parents=True, exist_ok=True)
    receipt_path = directory / f"setup-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:6]}.json"

    def save():
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")

    def verify_remote(path, private=False):
        prefix = ("run-as", PACKAGE) if private else ()
        size = int(shell(*prefix, "wc", "-c", path).stdout.split()[0])
        digest = shell(*prefix, "sha256sum", path, timeout=300).stdout.split()[0]
        if size != model["bytes"] or digest != model["sha256"]:
            raise RuntimeError(f"Reject device file: byte count or full SHA-256 differs: {path}")
        print(f"Verified device file: {path}\n{size} bytes; SHA-256 {digest}", flush=True)

    save()
    try:
        if args.verify_private:
            verify_remote(f"no_backup/models/{model['filename']}", private=True)
            receipt["app_private_copy_verified_this_run"] = True
            print("Persistent model installation verified. Offline inference is still a separate test.")
            return

        storage = shell("df", "-k", "/data").stdout
        print(storage, flush=True)
        available_kib = int(storage.splitlines()[-1].split()[3])
        if available_kib < 8 * 1024 * 1024:
            raise RuntimeError("Leave at least 8 GiB free for both model copies and runtime cache.")
        print("Installing Travis with existing app data preserved…", flush=True)
        adb("install", "-r", str(apk_path), capture=False, timeout=300)
        receipt["apk_installed_this_run"] = True
        save()
        destination = f"/sdcard/Download/{model['filename']}"
        exists = shell("test", "-f", destination, check=False)
        if exists.returncode == 0:
            verify_remote(destination)  # Refuse to overwrite an unknown/corrupt existing file.
        elif exists.returncode == 1:
            partial = destination + f".travis-{uuid.uuid4().hex[:8]}.partial"
            receipt["transfer_partial_path"] = partial
            save()
            print("Transferring the full model; keep USB connected…", flush=True)
            adb("push", str(model_path), partial, capture=False, timeout=1800)
            verify_remote(partial)
            shell("mv", "-n", partial, destination)
            verify_remote(destination)
        else:
            raise RuntimeError(f"Device storage check failed: {exists.stderr.strip()}")
        receipt["download_copy_verified_this_run"] = True
        receipt["download_path"] = destination
        save()
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", capture=False)
        print("\nOn the Pixel: Prepare → Import local Gemma model → Downloads → "
              + model["filename"] + ". Wait for verified success.")
        print("Then run: python3 scripts/pixel-setup.py --verify-private")
        print("No physical inference or offline navigation is claimed by this setup command.")
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        receipt["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        save()
        print(f"Receipt: {receipt_path}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError, OSError, ValueError, IndexError) as error:
        print(f"Setup stopped: {error}", file=sys.stderr)
        sys.exit(1)
