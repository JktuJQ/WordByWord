import argparse
import csv
import hashlib
import io
import json
import subprocess
from pathlib import Path

from prepare_podcasts import digest, fetch, finish_manifest

DEST = Path(__file__).resolve().parents[1] / 'fixtures/veche'


def prepare(offline, refresh=False):
    lock = json.loads((DEST / 'sources.json').read_text(encoding='utf-8'))
    base = f"https://huggingface.co/datasets/{lock['dataset']}/resolve/{lock['revision']}/"
    manifest = dict(dataset='veche-meetings-v1', revision=lock['revision'],
                    reference_quality='upstream_script_and_assembly_times_not_independently_reviewed',
                    samples=[])
    for source in lock['files']:
        if not source['path'].endswith('.csv'):
            continue
        path = DEST / 'cache' / source['path']
        fetch(path, base + source['path'], source.get('sha256'), offline=offline)
        data = path.read_bytes()
        blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
        if blob != source['git_blob']:
            raise ValueError('CSV Git blob mismatch')
        rows = []
        for row in csv.DictReader(io.StringIO(data.decode('utf-8-sig'))):
            start, end = float(row['start']), float(row['end'])
            if not 0 <= start < end or not row['text'].strip():
                raise ValueError('Invalid source transcript')
            rows.append(dict(speaker_id='speaker_' + row['speaker_id'],
                             start_ms=round(start * 1000), end_ms=round(end * 1000), text=row['text']))
        rows.sort(key=lambda row: (row['start_ms'], row['end_ms']))
        audio_path = source['path'].removesuffix('.csv') + '.wav'
        audio_source, = [row for row in lock['files'] if row['path'] == audio_path]
        audio = DEST / 'cache' / audio_path
        print('Preparing', audio_path, flush=True)
        fetch(audio, base + audio_path, audio_source['sha256'], offline=offline)
        duration = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
                         'format=duration', '-of', 'default=nw=1:nk=1', str(audio)], text=True))
        if any(row['end_ms'] > round(duration * 1000) + 1 for row in rows):
            raise ValueError('Transcript extends beyond audio')
        variant = Path(source['path']).parts[0].removeprefix('data_')
        meeting = Path(source['path']).stem
        sample_id = f'veche-{meeting}-{variant}'
        destination = DEST / 'audio' / (sample_id + '.flac')
        destination.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(audio),
                        '-c:a', 'flac', '-map_metadata', '-1', str(destination)], check=True)
        reference = 'transcripts/' + sample_id + '.json'
        write_json(DEST / reference, dict(transcript=rows))
        manifest['samples'].append(dict(id=sample_id, file='audio/' + destination.name,
            reference_json=reference, reference=' '.join(row['text'] for row in rows),
            seconds=duration, source_id=meeting, group='veche-shared-volunteers',
            split='development', protocol=variant, speakers=len({row['speaker_id'] for row in rows}),
            sha256=digest(destination)))
    finish_manifest(DEST, manifest, refresh)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--refresh-manifest', action='store_true')
    options = parser.parse_args()
    prepare(options.offline, options.refresh_manifest)


if __name__ == '__main__':
    main()
