import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import urllib.request
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'evaluation/fixtures/podcasts'
REVISION = 'e3aaa8a28211afe56c913fcacb26db029b1fb10e'
BASE = f'https://huggingface.co/datasets/leshinsky/ru-youtube-diarization/resolve/{REVISION}/'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def fetch(path, relative, expected=None, byte_range=None, offline=False):
    if path.exists() and (expected is None or digest(path) == expected):
        return
    if offline:
        raise ValueError(f'Missing or corrupt cached source: {path}')
    path.parent.mkdir(parents=True, exist_ok=True)
    headers = {} if byte_range is None else {'Range': f'bytes={byte_range[0]}-{byte_range[1]}'}
    request = urllib.request.Request(relative if relative.startswith('https://') else BASE + relative, headers=headers)
    temporary = path.with_suffix(path.suffix + '.part')
    limit = 128 * 1024 * 1024 if byte_range is None else byte_range[1] - byte_range[0] + 1
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
            if byte_range is not None:
                content_range = response.headers.get('Content-Range', '')
                prefix = f'bytes {byte_range[0]}-{byte_range[1]}/'
                if response.status != 206 or not content_range.startswith(prefix):
                    raise ValueError('Server ignored byte range; refusing full WAV download')
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > limit:
                    raise ValueError('Source exceeds download limit')
                output.write(chunk)
            if byte_range is not None and size != limit:
                raise ValueError('Truncated range response')
        if expected is not None and digest(temporary) != expected:
            raise ValueError(f'Checksum mismatch: {relative}')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def finish_manifest(destination, manifest, refresh=False):
    for sample in manifest['samples']:
        reference = destination / sample['reference_json']
        sample['reference_sha256'] = digest(reference)
        rows = json.loads(reference.read_text(encoding='utf-8'))['transcript']
        rttm = destination / 'rttm' / (sample['id'] + '.rttm')
        rttm.parent.mkdir(parents=True, exist_ok=True)
        rttm.write_text(''.join(
            f"SPEAKER {sample['id']} 1 {row['start_ms'] / 1000:.3f} "
            f"{(row['end_ms'] - row['start_ms']) / 1000:.3f} <NA> <NA> "
            f"{row['speaker_id']} <NA> <NA>\n" for row in rows), encoding='utf-8')
        sample['rttm'] = 'rttm/' + rttm.name
        sample['rttm_sha256'] = digest(rttm)
    path = destination / 'manifest.json'
    if path.exists() and not refresh:
        expected = json.loads(path.read_text(encoding='utf-8'))
        if manifest != expected:
            raise ValueError('Generated corpus differs from pinned manifest; review before --refresh-manifest')
    write_json(path, manifest)


def annotations(task):
    annotation, = task['annotations']
    if annotation['was_cancelled']:
        raise ValueError('Cancelled annotation')
    labels = {}
    texts = {}
    for row in annotation['result']:
        target = labels if row['type'] == 'labels' else texts if row['type'] == 'textarea' else None
        if target is None:
            continue
        if row['id'] in target:
            raise ValueError('Duplicate annotation region')
        target[row['id']] = row
    if labels.keys() != texts.keys():
        raise ValueError('Unpaired text and speaker labels')
    result = []
    invalid = []
    for key, label in labels.items():
        value = label['value']
        text = texts[key]['value']
        speaker, = value['labels']
        start, end = value['start'], value['end']
        if (start, end) != (text['start'], text['end']):
            raise ValueError('Mismatched annotation bounds')
        if not 0 <= start < end:
            invalid.append(dict(region=key, start=start, end=end, text=text['text']))
            continue
        if value['channel'] != 0 or text['channel'] != 0:
            raise ValueError('Unexpected annotation channel')
        result.append(dict(speaker_id=speaker.replace(' ', '_').lower(),
                           start_ms=round(start * 1000), end_ms=round(end * 1000),
                           text=' '.join(text['text']).strip()))
    return sorted(result, key=lambda row: (row['start_ms'], row['end_ms'], row['speaker_id'])), invalid


def cut(rows, nominal_start, nominal_end, duration):
    duration_ms = round(duration * 1000)
    ends = sorted({min(row['end_ms'], duration_ms) for row in rows})
    starts = sorted({row['start_ms'] for row in rows} | {0})
    safe = lambda point: not any(row['start_ms'] < point < min(row['end_ms'], duration_ms) for row in rows)
    start = next(point for point in starts if point >= nominal_start * 1000 and safe(point))
    end = next(point for point in ends if point >= nominal_end * 1000 and safe(point))
    if end - nominal_end * 1000 > 30000 or start - nominal_start * 1000 > 30000:
        raise ValueError('No safe turn boundary near requested window')
    selected = [dict(row, start_ms=row['start_ms'] - start,
                     end_ms=min(row['end_ms'], duration_ms) - start)
                for row in rows if start <= row['start_ms'] and min(row['end_ms'], duration_ms) <= end]
    return start, end, selected


def wav_prefix(source, cache, offline):
    header = cache / (source['id'] + '.header')
    fetch(header, source['audio'], source.get('header_sha256'), (0, 4095), offline)
    data = header.read_bytes()
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        raise ValueError('Not PCM WAV')
    position = 12
    while position + 8 <= len(data):
        kind, size = struct.unpack_from('<4sI', data, position)
        position += 8
        if kind == b'fmt ':
            encoding, channels, rate, _, alignment, bits = struct.unpack_from('<HHIIHH', data, position)
            if encoding != 1 or bits != 16 or alignment != channels * 2:
                raise ValueError('Expected PCM16 WAV')
        if kind == b'data':
            break
        position += size + size % 2
    else:
        raise ValueError('Missing WAV data chunk')
    frames = rate * 600
    raw = cache / (source['id'] + '.pcm')
    fetch(raw, source['audio'], source.get('prefix_sha256'),
          (position, position + frames * alignment - 1), offline)
    wav = cache / (source['id'] + '.wav')
    with wave.open(str(wav), 'wb') as output, raw.open('rb') as input_file:
        output.setparams((channels, 2, rate, 0, 'NONE', 'not compressed'))
        while chunk := input_file.read(1024 * 1024):
            output.writeframesraw(chunk)
    return wav, dict(header_sha256=digest(header), prefix_sha256=digest(raw),
                     byte_range=[position, position + frames * alignment - 1])


def prepare(lock, offline, refresh=False):
    manifest = dict(dataset='ru-continuous-conversations-v1', revision=REVISION,
                    reference_quality='upstream_corrected_predictions_not_independently_reviewed',
                    samples=[])
    for source in lock['sources']:
        print('Preparing', source['id'], flush=True)
        annotation_path = DEST / 'cache' / source['annotation']
        fetch(annotation_path, source['annotation'], source['annotation_sha256'], offline=offline)
        tasks = json.loads(annotation_path.read_text(encoding='utf-8'))
        task, = [task for task in tasks if task['data']['audio'] == source['audio']]
        rows, invalid = annotations(task)
        source['invalid_regions'] = invalid
        source['annotation_origins'] = {origin: sum(row.get('origin') == origin for row in task['annotations'][0]['result']) for origin in ['manual', 'prediction', 'prediction-changed']}
        if source.get('excluded'):
            continue
        duration = task['annotations'][0]['result'][0]['original_length']
        cache = DEST / 'cache'
        if source['audio'].endswith('.wav'):
            audio, hashes = wav_prefix(source, cache, offline)
            source.update(hashes)
            duration = 600
        else:
            audio = cache / Path(source['audio']).name
            fetch(audio, source['audio'], source['sha256'], offline=offline)
        measured = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
                         'format=duration', '-of', 'default=nw=1:nk=1', str(audio)], text=True))
        if abs(measured - duration) > 0.1:
            raise ValueError('Audio duration and annotations disagree')
        if not source['audio'].endswith('.wav') and any(row['end_ms'] > round(duration * 1000) + 20 for row in rows):
            raise ValueError('Annotation extends beyond audio')
        if source['audio'].endswith('.wav'):
            duration = max(row['end_ms'] / 1000 for row in rows
                if row['end_ms'] <= 600000 and not any(other['start_ms'] < row['end_ms'] < other['end_ms'] for other in rows))
        for index, start in enumerate([30, 150, 300, 450], 1):
            begin, end, selected = cut(rows, start, start + 90, duration)
            if any(min(row['start'], row['end']) * 1000 < end and max(row['start'], row['end']) * 1000 > begin for row in invalid):
                raise ValueError('Invalid upstream region intersects selected window')
            sample_id = f"{source['id']}-clip-{index}"
            destination = DEST / 'audio' / (sample_id + '.flac')
            destination.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-ss', str(begin / 1000),
                            '-i', str(audio), '-t', str((end - begin) / 1000), '-ac', '1',
                            '-ar', '16000', '-c:a', 'flac', '-map_metadata', '-1',
                            str(destination)], check=True)
            reference = 'transcripts/' + sample_id + '.json'
            write_json(DEST / reference, dict(transcript=selected))
            manifest['samples'].append(dict(id=sample_id, file='audio/' + destination.name,
                reference_json=reference, reference=' '.join(row['text'] for row in selected),
                seconds=(end - begin) / 1000, source_id=source['id'], group=source['group'],
                split=source['split'], protocol='short', source_start_ms=begin,
                source_end_ms=end, speakers=len({row['speaker_id'] for row in selected}),
                sha256=digest(destination)))
        if invalid:
            continue
        sample_id = source['id'] + '-long'
        destination = DEST / 'audio' / (sample_id + ('.flac' if source['audio'].endswith('.wav') else '.opus'))
        if destination.suffix == '.flac':
            subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(audio),
                            '-t', str(duration), '-ac', '1', '-ar', '16000', '-c:a', 'flac', '-map_metadata', '-1',
                            str(destination)], check=True)
        else:
            shutil.copyfile(audio, destination)
        selected = [dict(row, end_ms=min(row['end_ms'], round(duration * 1000)))
                    for row in rows if row['start_ms'] < duration * 1000]
        reference = 'transcripts/' + sample_id + '.json'
        write_json(DEST / reference, dict(transcript=selected))
        manifest['samples'].append(dict(id=sample_id, file='audio/' + destination.name,
            reference_json=reference, reference=' '.join(row['text'] for row in selected),
            seconds=duration, source_id=source['id'], group=source['group'], split=source['split'],
            protocol='long', source_start_ms=0, source_end_ms=round(duration * 1000),
            speakers=len({row['speaker_id'] for row in selected}), sha256=digest(destination)))
    finish_manifest(DEST, manifest, refresh)
    write_json(DEST / 'sources.json', lock)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--refresh-manifest', action='store_true')
    options = parser.parse_args()
    lock = json.loads((DEST / 'sources.json').read_text(encoding='utf-8'))
    prepare(lock, options.offline, options.refresh_manifest)


if __name__ == '__main__':
    main()
