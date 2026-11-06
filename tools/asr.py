import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import wave
import uuid


ROOT = Path(__file__).resolve().parents[1]
MODEL = "sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19"
HASHES = {
    "sherpa-onnx-2.0.1.aar": "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    MODEL + ".tar.bz2": "6c4bec4c4a70961fc4ec583bf5506089480a9a649c1816ef96b6e4fb8338cd71",
    "silero_vad.onnx": "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "install", "check"))
    parser.add_argument("--downloads", type=Path)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--only", choices=("all", "cancel"), default="all")
    args = parser.parse_args()
    local = ROOT / "local-models"
    models = local / "gigaam-v2"
    checks = local / "checks"
    if args.action == "prepare":
        if args.downloads is None:
            parser.error("prepare requires --downloads")
        for name, expected in HASHES.items():
            digest = hashlib.sha256()
            with (args.downloads / name).open("rb") as source:
                for chunk in iter(lambda: source.read(1048576), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected:
                raise SystemExit("Checksum mismatch: " + name)
        models.mkdir(parents=True, exist_ok=True)
        checks.mkdir(exist_ok=True)
        shutil.copyfile(args.downloads / "sherpa-onnx-2.0.1.aar", local / "sherpa-onnx-2.0.1.aar")
        shutil.copyfile(args.downloads / "silero_vad.onnx", models / "silero_vad.onnx")
        with tarfile.open(args.downloads / (MODEL + ".tar.bz2")) as archive:
            for name in ("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
                with archive.extractfile(MODEL + "/" + name) as source, (models / name).open("wb") as target:
                    shutil.copyfileobj(source, target)
            with archive.extractfile(MODEL + "/test_wavs/example.wav") as source:
                with wave.open(source, "rb") as recording:
                    short = recording.readframes(3200)
        for name, data in (("silence.wav", bytes(96000)), ("short.wav", short)):
            with wave.open(str(checks / name), "wb") as target:
                target.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                target.writeframes(data)
        print("Prepared local-models; no downloads performed")
        return

    def adb(*command, check=True):
        result = subprocess.run([args.adb, "-s", args.serial, *command], text=True, capture_output=True, check=check)
        return result.stdout

    package = "dev.wordbyword.app"
    if args.action == "install":
        for path in [models / name for name in ("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "silero_vad.onnx")] + [checks / "silence.wav", checks / "short.wav"]:
            if not path.is_file() or path.stat().st_size == 0:
                raise SystemExit(f"Missing {path}; run prepare first")
        print(adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk")))
        adb("shell", "am", "start", "-W", "-n", package + "/.MainActivity")
        destination = f"/sdcard/Android/data/{package}/files"
        adb("shell", "mkdir", "-p", destination + "/models", destination + "/checks")
        print(adb("push", str(models), destination + "/models/"))
        print(adb("push", str(checks) + "/.", destination + "/checks/"))
        for name in ("rec_0055.flac", "rec_0273.flac"):
            print(adb("push", str(ROOT / "test-data/diarization" / name), destination + "/checks/"))
        return

    print(adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")))
    state = {name: adb("shell", "settings", "get", "global", name).strip() for name in ("airplane_mode_on", "wifi_on", "mobile_data")}
    try:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        run_id = str(uuid.uuid4())
        result = adb("shell", "am", "instrument", "-w", "-e", "run_id", run_id, "-e", "only", args.only, package + ".test/dev.wordbyword.app.AsrDeviceCheck")
        (local / "device-output.txt").write_text(result)
        report = adb("shell", "run-as", package, "cat", "files/asr-checks.json")
        (local / "asr-checks.json").write_text(report)
        (local / "network.txt").write_text(adb("shell", "dumpsys", "connectivity"))
        print("Saved local-models/asr-checks.json")
        if json.loads(report).get("run_id") != run_id:
            raise SystemExit("Instrumentation did not produce a fresh report: " + result)
        if not json.loads(report).get("success"):
            raise SystemExit(report)
    finally:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if state["airplane_mode_on"] == "1" else "disable", check=False)
        adb("shell", "svc", "wifi", "enable" if state["wifi_on"] == "1" else "disable", check=False)
        adb("shell", "svc", "data", "enable" if state["mobile_data"] == "1" else "disable", check=False)


if __name__ == "__main__":
    main()
