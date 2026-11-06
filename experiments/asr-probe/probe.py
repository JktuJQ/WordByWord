import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import time
import uuid
import wave


ROOT = pathlib.Path(__file__).resolve().parent


MODEL = "sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19"

ARTIFACTS = {
    "sherpa-onnx-2.0.1.aar": "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    MODEL + ".tar.bz2": "6c4bec4c4a70961fc4ec583bf5506089480a9a649c1816ef96b6e4fb8338cd71",
    "silero_vad.onnx": "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
}

def write_wave(path, data):
    with wave.open(str(path), "wb") as target:
        target.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        target.writeframes(data)

def prepare_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("downloads", type=pathlib.Path)
    args = parser.parse_args()
    for name, expected in ARTIFACTS.items():
        path = args.downloads / name
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        digest = digest.hexdigest()
        if digest != expected:
            raise SystemExit(f"Checksum mismatch: {path}")
    vendor = ROOT / "vendor"
    inputs = ROOT / "inputs" / "probe"
    (inputs / "model").mkdir(parents=True, exist_ok=True)
    vendor.mkdir(exist_ok=True)
    shutil.copyfile(args.downloads / "sherpa-onnx-2.0.1.aar", vendor / "sherpa-onnx-2.0.1.aar")
    shutil.copyfile(args.downloads / "silero_vad.onnx", inputs / "silero_vad.onnx")
    with tarfile.open(args.downloads / (MODEL + ".tar.bz2")) as archive:
        for name in ("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "LICENSE", "README.md"):
            with archive.extractfile(MODEL + "/" + name) as source:
                (inputs / "model" / name).write_bytes(source.read())
        with archive.extractfile(MODEL + "/test_wavs/example.wav") as source:
            original = ROOT / "inputs" / "original.wav"
            original.write_bytes(source.read())
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(original), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(inputs / "example.wav")], check=True)
    with wave.open(str(inputs / "example.wav"), "rb") as source:
        data = source.readframes(source.getnframes())
    write_wave(inputs / "padded.wav", bytes(64000) + data + bytes(32000))
    write_wave(inputs / "silence.wav", bytes(96000))
    write_wave(inputs / "short.wav", data[:6400])
    fixture = ROOT.parents[1] / "test-data" / "diarization" / "rec_0055.flac"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(fixture), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(inputs / "two-speakers.wav")], check=True)
    print(f"Prepared {inputs}; no downloads performed")

PACKAGE = "dev.wordbyword.asrprobe"

ACTIVITY = PACKAGE + "/.ProbeActivity"

def run_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--prepare-device", action="store_true")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--vad-only", action="store_true")
    parser.add_argument("--wave", default="example.wav")
    parser.add_argument("--padding-ms", type=int, default=0)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()

    def adb(*command, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], text=True, capture_output=True, check=check)

    if args.prepare_device:
        print(adb("install", "-r", str(ROOT / "build/outputs/apk/debug/WordByWordAsrProbe-debug.apk")).stdout, flush=True)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        print(adb("push", str(ROOT / "inputs/probe"), f"/sdcard/Android/data/{PACKAGE}/files/").stdout, flush=True)
    states = {}
    run_id = str(uuid.uuid4())
    try:
        if args.offline:
            for setting in ("airplane_mode_on", "wifi_on", "mobile_data"):
                states[setting] = adb("shell", "settings", "get", "global", setting).stdout.strip()
            adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
            adb("shell", "svc", "wifi", "disable")
            adb("shell", "svc", "data", "disable")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", ACTIVITY, "--ez", "run", "true", "--es", "run_id", run_id,
            "--es", "wave", args.wave, "--ei", "padding_ms", str(args.padding_ms), "--ez", "full", str(not args.vad_only).lower())
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            result = adb("shell", "run-as", PACKAGE, "cat", "files/result.json", check=False)
            if result.returncode == 0:
                try:
                    report = json.loads(result.stdout)
                except json.JSONDecodeError:
                    report = {}
                if report.get("run_id") == run_id:
                    report["offline_requested"] = args.offline
                    if args.offline:
                        report["network_state"] = adb("shell", "dumpsys", "connectivity").stdout
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    args.output.write_text(json.dumps(report, ensure_ascii=False, separators=(",", ":")) + "\n")
                    print(f"Saved {args.output}: success={report.get('success')}", flush=True)
                    if not report.get("success"):
                        raise SystemExit(report.get("error"))
                    return
            time.sleep(2)
        raise SystemExit("Timed out; inspect adb logcat -s AsrProbe AndroidRuntime")
    finally:
        if states:
            adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if states["airplane_mode_on"] == "1" else "disable", check=False)
            adb("shell", "svc", "wifi", "enable" if states["wifi_on"] == "1" else "disable", check=False)
            adb("shell", "svc", "data", "enable" if states["mobile_data"] == "1" else "disable", check=False)

def verify_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=pathlib.Path, default=ROOT / "results.json")
    args = parser.parse_args()
    if args.reports.is_dir():
        reports = {path.stem: json.loads(path.read_text()) for path in args.reports.glob("*.json")}
    else:
        reports = json.loads(args.reports.read_text())
    if not reports:
        raise SystemExit("No reports")
    for name, report in reports.items():
        assert report["success"], name
        limit = report["audio_seconds"] * 1000
        segments = ([report["full"]] if "full" in report else []) + report["vad_segments"]
        for segment in segments:
            times = segment["token_start_ms"]
            assert len(times) == len(segment["tokens"]), name
            assert times == sorted(times), name
            assert all(0 <= value <= limit + 1 for value in times), name
            assert 0 <= segment["start_ms"] <= segment["end_ms"] <= limit + 1, name
        if report.get("offline_requested"):
            assert "Active default network: none" in report["network_state"], name
    online = reports["example-online"]["full"]
    offline = reports["example-offline"]["full"]
    padded = reports["padded-offline"]["full"]
    assert online == offline
    assert padded["text"] == online["text"]
    assert 1800 < padded["token_start_ms"][0] - online["token_start_ms"][0] < 2200
    assert reports["example-context"]["vad_segments"][0]["text"].startswith("ничьих")
    for name in ("silence", "short"):
        assert reports[name]["full"]["text"] == ""
        assert reports[name]["vad_segments"] == []
    print(f"PASS: {len(reports)} Android reports; bounds, token counts, offline repeat, silence, offset, VAD context")

def main():
    commands = {"prepare": prepare_main, "run": run_main, "verify": verify_main}
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print("Usage: python3 probe.py {prepare|run|verify} [options]")
        return
    command = sys.argv.pop(1)
    if command not in commands:
        raise SystemExit("Expected prepare, run, or verify")
    commands[command]()


if __name__ == "__main__":
    main()
