#!/usr/bin/env python3
"""Build small, composed Dialogs fixtures. Python 3.10+ and FFmpeg required.

Network is used only to obtain the pinned source data, never for inference.
Downloaded WAVs stay in --cache (outside the fixture directory by default).
Use --offline to rebuild exclusively from that cache.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess
import urllib.request
import wave


ROOT = Path(__file__).resolve().parents[2]
REPO = "langswap/dialogs-ru-emotional-conversations"
RATE = 44100
GAP_MS = 250


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def download(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def prepare(args):
    ffmpeg_version = subprocess.run(["ffmpeg", "-version"], check=True,
                                    stdout=subprocess.PIPE, text=True).stdout.splitlines()[0]
    output = args.output.resolve()
    lock_path = output / "composition.json"
    lock = json.loads(lock_path.read_text()) if lock_path.exists() else None
    if lock and (lock["sample_rate"] != RATE or lock["gap_ms"] != GAP_MS):
        raise ValueError("Composition settings differ from this script")
    if args.offline and lock is None:
        raise ValueError("Offline rebuild requires composition.json")
    available = None
    if lock:
        revision = lock["source_revision"]
    else:
        info = json.loads(download(f"https://huggingface.co/api/datasets/{REPO}"))
        revision = info["sha"]
        available = {item["rfilename"] for item in info["siblings"]}
    cache = args.cache.resolve() / revision
    cache.mkdir(parents=True, exist_ok=True)
    base = f"https://huggingface.co/datasets/{REPO}/resolve/{revision}/"
    previous_hashes = lock["source_sha256"] if lock else {}
    source_hashes = {}

    def source(name):
        path = cache / name
        if not path.exists():
            if args.offline:
                raise FileNotFoundError(f"Not cached: {path}")
            print(f"Downloading {name}", flush=True)
            data = download(base + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix(path.suffix + ".part")
            temporary.write_bytes(data)
            temporary.replace(path)
        data = path.read_bytes()
        digest = sha(data)
        if lock and previous_hashes.get(name) != digest:
            raise ValueError(f"Source hash mismatch or unlocked source: {name}")
        source_hashes[name] = digest
        return data

    metadata = source("metadata.csv")
    license_data = source("LICENSE.md")
    readme_data = source("README.md")
    rows = {r["audio_path"]: r for r in csv.DictReader(io.StringIO(metadata.decode()), delimiter="|")}
    # Preserve numbered source order in the two-speaker examples. The third
    # example deliberately combines food-related replies from two sessions.
    selections = [
        ("dialogs_md", [f"masha_dima_part1_{i}" for i in range(1, 13)]),
        # Metadata lists clips 8 and 9, but those WAVs are absent upstream.
        ("dialogs_ms", [f"masha_sveta_part1_{i}" for i in [1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14]]),
        ("dialogs_msd", [
            "masha_sveta_part1_1", "masha_sveta_part1_2",
            "masha_sveta_part1_3", "masha_dima_part10_6",
            "masha_sveta_part1_6", "masha_sveta_part1_7",
            "masha_dima_part10_2", "masha_sveta_part1_10",
            "masha_sveta_part1_11", "masha_sveta_part1_12",
            "masha_dima_part10_4", "masha_dima_part10_12",
        ]),
    ]
    if lock:
        selections = [(r["id"], [Path(p).stem for p in r["sources"]]) for r in lock["recordings"]]
    if available is not None:
        missing = {f"wavs/{stem}.wav" for _, stems in selections for stem in stems} - available
        if missing:
            alternatives = sorted(p for p in available if p.startswith("wavs/masha_dima_part10_")
                                  and int(Path(p).stem.rsplit("_", 1)[1]) <= 15)
            raise ValueError(f"Missing upstream WAVs: {sorted(missing)}; alternatives: {alternatives}")
    for folder in ["audio", "transcripts", "rttm"]:
        (output / folder).mkdir(parents=True, exist_ok=True)
    samples, recordings = [], []
    for identifier, stems in selections:
        pcm, segments, provenance = bytearray(), [], []
        speaker_map = {}
        for index, stem in enumerate(stems):
            name = f"wavs/{stem}.wav"
            row = rows[name]
            original = source(name)
            with wave.open(io.BytesIO(original), "rb") as wav:
                source_info = {"sample_rate": wav.getframerate(), "channels": wav.getnchannels(),
                               "sample_width": wav.getsampwidth(), "frames": wav.getnframes()}
            # Standardize rate/channel layout without trimming speech or silence.
            decoded = subprocess.run(
                ["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "s16le", "-acodec", "pcm_s16le",
                 "-ar", str(RATE), "-ac", "1", "pipe:1"],
                input=original, stdout=subprocess.PIPE, check=True).stdout
            if not decoded or len(decoded) % 2:
                raise ValueError(f"Invalid PCM: {name}")
            if index:
                pcm.extend(b"\0\0" * round(RATE * GAP_MS / 1000))
            start = len(pcm) // 2
            pcm.extend(decoded)
            end = len(pcm) // 2
            speaker = speaker_map.setdefault(row["speaker_id"], f"speaker_{len(speaker_map) + 1}")
            segments.append({"speaker_id": speaker, "start_ms": round(start * 1000 / RATE),
                             "end_ms": round(end * 1000 / RATE), "text": row["text"]})
            provenance.append({"source": name, "source_speaker_id": row["speaker_id"],
                               "speaker_id": speaker, "start_sample": start, "end_sample": end,
                               "source_audio": source_info, "emotion": row["emotion"]})
        duration = len(pcm) / (2 * RATE)
        if not 60 <= duration <= 90:
            raise ValueError(f"{identifier}: duration {duration} outside 60–90 seconds")
        audio_path = output / "audio" / f"{identifier}.wav"
        with wave.open(str(audio_path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(RATE)
            wav.writeframes(pcm)
        write_json(output / "transcripts" / f"{identifier}.json", {"transcript": segments})
        # Provisional full-clip intervals, NOT manually verified speech activity.
        rttm = "".join(
            f"SPEAKER {identifier} 1 {p['start_sample'] / RATE:.6f} "
            f"{(p['end_sample'] - p['start_sample']) / RATE:.6f} <NA> <NA> "
            f"{p['speaker_id']} <NA> <NA>\n" for p in provenance)
        (output / "rttm" / f"{identifier}.rttm").write_text(rttm)
        samples.append({"file": f"audio/{identifier}.wav", "reference": " ".join(s["text"] for s in segments),
                        "seconds": duration, "sample_rate": RATE, "speakers": len(speaker_map),
                        "sha256": sha(audio_path.read_bytes()), "split": "smoke",
                        "text_review": "upstream_transcript_not_independently_listened",
                        "timing_review": "clip_boundaries_not_verified_speech_boundaries"})
        recordings.append({"id": identifier, "sources": [p["source"] for p in provenance],
                           "speaker_map": speaker_map, "segments": provenance})
        print(f"{identifier}: {duration:.3f} s, {len(speaker_map)} speakers, {len(segments)} clips", flush=True)
    write_json(output / "manifest.json", {
        "dataset": "dialogs-composed-smoke", "source": f"https://huggingface.co/datasets/{REPO}",
        "revision": revision, "license": "OpenRAIL (see LICENSE.md)",
        "selection": "Fixed numbered clips; synthetic concatenation of real recorded speech",
        "samples": samples})
    composition = {"source_repo": REPO, "source_revision": revision, "sample_rate": RATE,
                   "gap_ms": GAP_MS, "format": "PCM16 mono WAV", "source_sha256": source_hashes,
                   "ffmpeg_version": ffmpeg_version,
                   "recordings": recordings}
    write_json(lock_path, composition)
    (output / "LICENSE.md").write_bytes(license_data)
    # Preserve upstream description as evidence without duplicating the full CSV.
    (output / "UPSTREAM_README.md").write_bytes(readme_data)
    paths = sorted(p for p in output.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    (output / "SHA256SUMS").write_text("".join(f"{sha(p.read_bytes())}  {p.relative_to(output).as_posix()}\n" for p in paths))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "evaluation/results/dialogs-source-cache")
    parser.add_argument("--output", type=Path, default=ROOT / "evaluation/fixtures/dialogs")
    parser.add_argument("--offline", action="store_true")
    prepare(parser.parse_args())
