import argparse
import json
import subprocess
from pathlib import Path

from prepare_podcasts import digest


def check(path, audio):
    manifest = json.loads(path.read_text(encoding='utf-8'))
    root = path.parent
    identifiers = set()
    groups = {}
    source_splits = {}
    for sample in manifest['samples']:
        identity = sample['id']
        if identity in identifiers or not sample['seconds'] > 0:
            raise ValueError('Duplicate ID or invalid duration')
        identifiers.add(identity)
        for key, group in [('group', groups), ('source_id', source_splits)]:
            previous = group.setdefault(sample[key], sample['split'])
            if previous != sample['split']:
                raise ValueError('Source or speaker group leaks across splits')
        reference = root / sample['reference_json']
        if digest(reference) != sample['reference_sha256']:
            raise ValueError('Reference checksum mismatch')
        rttm = root / sample['rttm']
        if digest(rttm) != sample['rttm_sha256']:
            raise ValueError('RTTM checksum mismatch')
        turns = json.loads(reference.read_text(encoding='utf-8'))['transcript']
        if ' '.join(row['text'] for row in turns) != sample['reference']:
            raise ValueError('ASR text and transcript disagree')
        if len({row['speaker_id'] for row in turns}) != sample['speakers']:
            raise ValueError('Speaker count mismatch')
        if any(not 0 <= row['start_ms'] < row['end_ms'] <= round(sample['seconds'] * 1000) + 1 for row in turns):
            raise ValueError('Invalid transcript interval')
        lines = rttm.read_text(encoding='utf-8').splitlines()
        if len(lines) != len(turns):
            raise ValueError('RTTM and JSON have different turn counts')
        for row, line in zip(turns, lines):
            fields = line.split()
            if len(fields) != 10 or fields[0] != 'SPEAKER' or fields[1] != identity or fields[2] != '1' or fields[7] != row['speaker_id']:
                raise ValueError('RTTM speaker or recording mismatch')
            if abs(float(fields[3]) * 1000 - row['start_ms']) > 0.001 or abs(float(fields[4]) * 1000 - (row['end_ms'] - row['start_ms'])) > 0.001:
                raise ValueError('RTTM and JSON timings disagree')
        if audio:
            file = root / sample['file']
            if digest(file) != sample['sha256']:
                raise ValueError('Audio checksum mismatch')
            duration = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
                             'format=duration', '-of', 'default=nw=1:nk=1', str(file)], text=True))
            if abs(duration - sample['seconds']) > 0.05:
                raise ValueError('Audio and reference duration mismatch')
    print(f"{path}: {len(identifiers)} cases, {len(source_splits)} source recordings; audio={audio}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest', type=Path, nargs='+')
    parser.add_argument('--audio', action='store_true')
    args = parser.parse_args()
    for path in args.manifest:
        check(path, args.audio)


if __name__ == '__main__':
    main()
