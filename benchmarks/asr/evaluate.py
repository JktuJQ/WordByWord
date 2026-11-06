import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import unicodedata
import uuid
import zipfile


ROOT = Path(__file__).resolve().parent


def normalize(text):
    text = unicodedata.normalize("NFKC", text).lower().replace("ё", "е")
    return " ".join("".join(c if c.isalnum() else " " for c in text).split())


def edits(reference, hypothesis):
    previous = [(i, 0, i, 0) for i in range(len(hypothesis) + 1)]
    for i, a in enumerate(reference, 1):
        current = [(i, 0, 0, i)]
        for j, b in enumerate(hypothesis, 1):
            if a == b:
                current.append(previous[j-1])
            else:
                d, s, ins, dele = previous[j-1]
                sub = (d+1, s+1, ins, dele)
                d, s, ins, dele = current[j-1]
                insertion = (d+1, s, ins+1, dele)
                d, s, ins, dele = previous[j]
                deletion = (d+1, s, ins, dele+1)
                current.append(min((sub, insertion, deletion), key=lambda v: v[0]))
        previous = current
    return previous[-1]


def score():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    report = json.loads((ROOT / "android-results.json").read_text())
    assert report["success"] and len(report["runs"]) == len(manifest["samples"])
    runs = {r["file"]: r for r in report["runs"]}
    rows = []
    for sample in manifest["samples"]:
        run = runs[sample["file"]]
        assert run["status"] == "READY"
        hypothesis = " ".join(s["text"] for s in run["segments"])
        ref, hyp = normalize(sample["reference"]), normalize(hypothesis)
        error, substitutions, insertions, deletions = edits(ref.split(), hyp.split())
        char_error = edits(ref.replace(" ", ""), hyp.replace(" ", ""))[0]
        rows.append({"file": sample["file"], "reference": sample["reference"], "hypothesis": hypothesis,
                     "words": len(ref.split()), "word_errors": error, "substitutions": substitutions,
                     "insertions": insertions, "deletions": deletions, "characters": len(ref.replace(" ", "")),
                     "character_errors": char_error, "audio_seconds": sample["seconds"], "elapsed_ms": run["elapsed_ms"]})
    totals = {k: sum(row[k] for row in rows) for k in ("words", "word_errors", "substitutions", "insertions", "deletions", "characters", "character_errors", "audio_seconds", "elapsed_ms")}
    totals.update(wer=totals["word_errors"]/totals["words"], cer=totals["character_errors"]/totals["characters"],
                  rtf=totals["elapsed_ms"]/1000/totals["audio_seconds"], exact_utterances=sum(normalize(r["reference"])==normalize(r["hypothesis"]) for r in rows), samples=len(rows))
    result = {"normalization": "NFKC, lowercase, ё→е, punctuation to spaces, collapsed whitespace; CER excludes spaces; corpus micro averages",
              "totals": totals, "samples": rows}
    (ROOT / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
    print(json.dumps(totals, ensure_ascii=False, indent=2))


def run(args):
    package = "dev.wordbyword.app"
    def adb(*command, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], text=True, capture_output=True, check=check).stdout
    manifest = json.loads((ROOT / "manifest.json").read_text())
    cache = ROOT / "work"
    cache.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / "audio.zip") as archive:
        for sample in manifest["samples"]:
            data = archive.read(sample["file"])
            assert hashlib.sha256(data).hexdigest() == sample["sha256"]
            (cache / sample["file"]).write_bytes(data)
    adb("install", "-r", str(ROOT.parents[1] / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    adb("push", str(cache) + "/.", f"/sdcard/Android/data/{package}/files/checks/")
    states = {n: adb("shell", "settings", "get", "global", n).strip() for n in ("airplane_mode_on", "wifi_on", "mobile_data")}
    try:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        nonce = str(uuid.uuid4())
        output = adb("shell", "am", "instrument", "-w", "-e", "run_id", nonce, "-e", "only", "benchmark", "-e", "files", ",".join(s["file"] for s in manifest["samples"]), package + ".test/dev.wordbyword.app.AsrDeviceCheck")
        report = json.loads(adb("shell", "run-as", package, "cat", "files/asr-checks.json"))
        assert report.get("run_id") == nonce, output
        (ROOT / "android-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        network = adb("shell", "dumpsys", "connectivity")
        assert "Active default network: none" in network
        assert report["success"], report.get("error")
    finally:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if states["airplane_mode_on"]=="1" else "disable", check=False)
        adb("shell", "svc", "wifi", "enable" if states["wifi_on"]=="1" else "disable", check=False)
        adb("shell", "svc", "data", "enable" if states["mobile_data"]=="1" else "disable", check=False)
    score()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("run", "score"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    {"score": score, "run": lambda: run(args)}[args.action]()
