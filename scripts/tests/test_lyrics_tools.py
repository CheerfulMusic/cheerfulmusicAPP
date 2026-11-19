import hashlib, importlib.util, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + '.py'))
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
align = module('align-sample-lyrics')
prepare = module('prepare-sample-lyrics')

class LyricsToolsTest(unittest.TestCase):
    def test_headers_are_not_treated_as_sung_words(self):
        self.assertEqual(align.lyric_lines('[Intro - Whispered]\n原稿一句\nverse1\n原稿二句\n（prechorus）\n原稿三句\n《歌名》'), ['原稿一句','原稿二句','原稿三句'])
    def test_repeated_sections_keep_their_own_actual_time(self):
        raw=[{'text':'原句','start':0,'end':1},{'text':'别句','start':2,'end':3},{'text':'原句','start':4,'end':5}]
        value=align.restore_lines(['原句','别句','原句'],raw)
        self.assertEqual([v['start'] for v in value],[0,2,4])
        self.assertEqual([v['text'] for v in value],['原句','别句','原句'])
    def test_missing_alignment_does_not_fabricate_line_times(self):
        with self.assertRaises(ValueError):align.restore_lines(['没有这句'],[{'text':'完全不同','start':0,'end':1}])
    def test_authored_spacing_and_punctuation_are_preserved(self):
        result=align.restore_lines(['走单骑  着铁衣'],[{'text':'走单骑','start':1,'end':2},{'text':'着铁衣','start':3,'end':4}])
        self.assertEqual(result[0]['text'],'走单骑  着铁衣');self.assertEqual(result[0]['start'],1);self.assertEqual(result[0]['end'],4)
    def test_chunks_require_exact_audio_and_cover_all_authored_lines(self):
        plan={'audioSha256':'known','chunks':[{'start':10,'end':30,'lines':[0,1]}, {'start':60,'end':80,'lines':[1]}]}
        rows=align.reviewed_chunks(plan,['a','b'],200,'known')
        self.assertEqual(rows[1],(60,80,['b']))
        with self.assertRaises(ValueError):align.reviewed_chunks(plan,['a','b'],200,'changed')
        with self.assertRaises(ValueError):align.reviewed_chunks(plan,['a','b','missing'],200,'known')
        plan['chunks'][0]['end']=191
        with self.assertRaises(ValueError):align.reviewed_chunks(plan,['a','b'],200,'known')
    def test_audio_and_candidate_versions_must_both_match(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'audio.mp3';file.write_bytes(b'local-fixture-audio');digest=hashlib.sha256(file.read_bytes()).hexdigest()
            asset={'sha256':digest,'trackId':'song','key':'immutable/audio/hash/song.mp3'}
            candidate={'song':'song','audioSha256':digest,'duration':20,'segments':[{'text':'test','words':[{'text':'line','start':i,'end':i+.5} for i in range(10)]}]}
            value=prepare.prepare(candidate,file,asset);self.assertEqual(value['audioSha256'],digest)
            candidate['audioSha256']='0'*64
            with self.assertRaises(ValueError):prepare.prepare(candidate,file,asset)
            candidate['audioSha256']=digest;file.write_bytes(b'changed-preview')
            with self.assertRaises(ValueError):prepare.prepare(candidate,file,asset)

if __name__=='__main__':unittest.main()
