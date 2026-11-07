import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from check_corpus import check
from prepare_podcasts import annotations, cut


class CorpusTest(unittest.TestCase):
    def task(self):
        return dict(annotations=[dict(was_cancelled=False, result=[
            dict(id='r', type='labels', value=dict(start=1, end=2, channel=0, labels=['Speaker 1'])),
            dict(id='r', type='textarea', value=dict(start=1, end=2, channel=0, text=['Привет']))])])

    def test_pairing_uses_region_id_and_preserves_text(self):
        task = self.task()
        task['annotations'][0]['result'].reverse()
        rows, invalid = annotations(task)
        self.assertEqual(rows, [dict(speaker_id='speaker_1', start_ms=1000, end_ms=2000, text='Привет')])
        self.assertFalse(invalid)

    def test_reversed_bounds_are_quarantined_not_swapped(self):
        task = self.task()
        for row in task['annotations'][0]['result']:
            row['value']['start'], row['value']['end'] = 3, 2
        rows, invalid = annotations(task)
        self.assertFalse(rows)
        self.assertEqual((invalid[0]['start'], invalid[0]['end']), (3, 2))

    def test_unpaired_annotation_is_rejected(self):
        task = self.task()
        task['annotations'][0]['result'].pop()
        with self.assertRaises(ValueError):
            annotations(task)

    def test_mismatched_text_timing_is_rejected(self):
        task = self.task()
        task['annotations'][0]['result'][1]['value']['start'] = 0
        with self.assertRaises(ValueError):
            annotations(task)

    def test_crop_does_not_cut_overlapping_speaker(self):
        rows = [dict(start_ms=1000, end_ms=5000, speaker_id='a', text='один'),
                dict(start_ms=4000, end_ms=6000, speaker_id='b', text='два'),
                dict(start_ms=7000, end_ms=9000, speaker_id='a', text='три')]
        start, end, selected = cut(rows, 0, 4.5, 10)
        self.assertEqual((start, end), (0, 6000))
        self.assertEqual(len(selected), 2)
        self.assertEqual(selected[1]['text'], 'два')

    def test_committed_corpora_are_consistent_without_audio(self):
        for name in ['podcasts', 'veche']:
            check(SCRIPTS.parent / 'fixtures' / name / 'manifest.json', False)

    def test_split_leakage_fails_validation(self):
        source = SCRIPTS.parent / 'fixtures/podcasts/manifest.json'
        manifest = json.loads(source.read_text(encoding='utf-8'))
        first, = [row for row in manifest['samples'] if row['id'] == 'astrology-clip-1']
        duplicate = copy.deepcopy(first)
        duplicate['id'] = 'wrongly-independent-test'
        duplicate['split'] = 'evaluation'
        manifest['samples'].append(duplicate)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'manifest.json'
            for row in manifest['samples']:
                row['reference_json'] = str(source.parent / row['reference_json'])
                row['rttm'] = str(source.parent / row['rttm'])
            target.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'leaks across splits'):
                check(target, False)


if __name__ == '__main__':
    unittest.main()
