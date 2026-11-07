import argparse
import hashlib
import pathlib
import shutil
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent

ARTIFACTS = {
    "sherpa-onnx-2.0.1.aar": "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2": (
        "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
    ),
    "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx": (
        "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
    ),
}


def sha256sum(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("downloads", type=pathlib.Path)
    args = parser.parse_args()

    for name, expected in ARTIFACTS.items():
        path = args.downloads / name
        actual = sha256sum(path)
        if actual != expected:
            raise SystemExit(f"Checksum mismatch: {path} (got {actual})")

    vendor = ROOT / "vendor"
    assets = ROOT / "src" / "main" / "assets" / "diarization"
    vendor.mkdir(exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)

    shutil.copyfile(
        args.downloads / "sherpa-onnx-2.0.1.aar", vendor / "sherpa-onnx-2.0.1.aar"
    )

    with tarfile.open(
        args.downloads / "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    ) as archive:
        with archive.extractfile(
            "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"
        ) as source:
            (assets / "segmentation.int8.onnx").write_bytes(source.read())

    shutil.copyfile(
        args.downloads / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        assets / "embedding.onnx",
    )

    print("Prepared:", vendor / "sherpa-onnx-2.0.1.aar")
    print("Prepared:", assets / "segmentation.int8.onnx")
    print("Prepared:", assets / "embedding.onnx")


if __name__ == "__main__":
    main()
