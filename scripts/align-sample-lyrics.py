"""Align authored lyrics to local preview audio. No database or model API is used."""
import argparse, difflib, hashlib, json, math, re
from pathlib import Path

def lyric_lines(text):
    header = re.compile(r'^(?:verse|pre[- ]?chorus|chorus|bridge|intro|outro|interlude|instrumental)\s*\d*$', re.I)
    return [line for value in text.splitlines() if (line := value.strip())
            and not (line.startswith('[') and line.endswith(']'))
            and not (line.startswith('《') and line.endswith('》')) and not header.fullmatch(line.strip('()（）【】 '))]

def normalize(text):
    return ''.join(c.lower() for c in text if c.isalnum())

def preview_audio(song, audio_dir, assets):
    if assets is None:
        return audio_dir / ('sample-' + song + '-v1-preview-128.mp3')
    asset = assets.get(song)
    if not asset or not asset['path'].startswith('/media/') or not asset['path'].endswith('.mp3'):
        raise ValueError('Missing preview audio in the checked manifest: ' + song)
    path = audio_dir / Path(asset['path']).name
    with path.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != asset['sha256']:
            raise ValueError('Preview audio does not match the release manifest: ' + song)
    return path

def restore_lines(lines, aligned):
    expected = ''.join(normalize(line) for line in lines)
    observed, word_indices = '', []
    for index, word in enumerate(aligned):
        value = normalize(word['text'])
        observed += value
        word_indices.extend([index] * len(value))
    mapping = {}
    for block in difflib.SequenceMatcher(None, expected, observed, autojunk=False).get_matching_blocks():
        for offset in range(block.size):
            mapping[block.a + offset] = word_indices[block.b + offset]
    result, cursor = [], 0
    for line in lines:
        size = len(normalize(line))
        matched = [mapping[i] for i in range(cursor, cursor + size) if i in mapping]
        cursor += size
        if not size or len(matched) / size < .9:
            raise ValueError('Authored line is not fully represented in the alignment: ' + line)
        timed = [aligned[i] for i in matched if aligned[i]['end'] > aligned[i]['start']]
        if not timed:
            raise ValueError('Authored line has no valid audio timestamps: ' + line)
        start, end = timed[0]['start'], timed[-1]['end']
        if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start:
            raise ValueError('Invalid line time')
        if result and start < result[-1]['start']:
            raise ValueError('Alignment has moved backwards')
        result.append({'start': start, 'end': end, 'text': line,
                       'words': [{'text': line + '\n', 'start': start, 'end': end}]})
    return result

def reviewed_chunks(plan, lines, duration, audio_hash):
    """Use reviewed audio boundaries, never distribute lyric times by duration."""
    if not plan or plan.get('audioSha256') != audio_hash:
        raise ValueError('A version-bound, reviewed chunk plan is required')
    covered, previous, result = set(), -1, []
    for chunk in plan['chunks']:
        start, end, indices = chunk['start'], chunk['end'], chunk['lines']
        if not (0 <= start < end <= duration + .1 and end - start <= 180 and start >= previous):
            raise ValueError('Invalid or oversized audio chunk')
        if not indices or any(type(i) is not int or i < 0 or i >= len(lines) for i in indices):
            raise ValueError('Invalid authored line references')
        covered.update(indices); previous = start
        result.append((start, min(end, duration), [lines[i] for i in indices]))
    if covered != set(range(len(lines))):
        raise ValueError('Chunk plan omits authored lines')
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lyrics', type=Path, required=True)
    parser.add_argument('--audio-dir', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--model-sha256', default='47831d0e82f96b20e9034dba01a075ee06436654719f6a68289e49f1b65ce0e7')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--ids', nargs='*')
    parser.add_argument('--manifest', type=Path, help='Checked CDN manifest for hash-named preview files')
    parser.add_argument('--language', choices=['English', 'Chinese'], help='Override the language for this alignment batch')
    parser.add_argument('--dtype', choices=['float32', 'bfloat16', 'float16'], default='float32')
    parser.add_argument('--device', default='cpu', choices=['cpu', 'cuda:0'])
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--chunks', type=Path, help='Reviewed audio windows and authored line references, bound to audio SHA-256')
    args = parser.parse_args()
    with (args.model_dir / 'model.safetensors').open('rb') as model_file:
        if hashlib.file_digest(model_file, 'sha256').hexdigest() != args.model_sha256:
            raise ValueError('Alignment model SHA-256 does not match the reviewed version')
    import torch
    from qwen_asr import Qwen3ForcedAligner
    import soundfile
    torch.set_num_threads(max(1, args.threads))
    if args.device.startswith('cuda') and not torch.cuda.is_available():
        raise ValueError('Requested CUDA device is unavailable; no implicit runtime switch')
    model = Qwen3ForcedAligner.from_pretrained(str(args.model_dir), device_map=args.device, dtype=getattr(torch,args.dtype),
                                             attn_implementation='sdpa', local_files_only=True)
    songs = json.loads(args.lyrics.read_text(encoding='utf-8'))
    plans = json.loads(args.chunks.read_text(encoding='utf-8')) if args.chunks else {}
    assets = {a['trackId']:a for a in json.loads(args.manifest.read_text(encoding='utf-8'))['assets'] if a['kind']=='audio'} if args.manifest else None
    args.output_dir.mkdir(parents=True, exist_ok=True)
    failed = False
    for song, text in songs.items():
        if args.ids and song not in args.ids:
            continue
        audio = preview_audio(song, args.audio_dir, assets)
        language = args.language or ('English' if song in {'after-midnight','if-you-wanna-feel-me','where-the-wind-goes'} else 'Chinese')
        with audio.open('rb') as audio_file:
            audio_hash = hashlib.file_digest(audio_file, 'sha256').hexdigest()
        target = args.output_dir / (song + '.aligned.json')
        plan = plans.get(song)
        plan_hash = hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()
        if target.exists():
            cached = json.loads(target.read_text(encoding='utf-8'))
            if cached.get('audioSha256') == audio_hash and cached.get('authoredText') == text and cached.get('planSha256') == plan_hash and cached.get('language',language if not args.language else None)==language:
                print(json.dumps({'song': song, 'state': 'CACHED'}), flush=True)
                continue
        lines = lyric_lines(text)
        if not lines:
            failed = True
            continue
        raw_file = args.output_dir / (song + '.alignment.raw.json')
        saved = json.loads(raw_file.read_text(encoding='utf-8')) if raw_file.exists() else {}
        text_hash = hashlib.sha256(text.encode('utf-8')).hexdigest()
        if isinstance(saved, dict) and saved.get('audioSha256') == audio_hash and saved.get('textSha256') == text_hash and saved.get('planSha256') == plan_hash and saved.get('language',language if not args.language else None)==language and 'segments' in saved:
            aligned = saved['words']
        else:
            print(json.dumps({'song': song, 'state': 'ALIGNING', 'lines': len(lines)}), flush=True)
            duration = soundfile.info(str(audio)).duration
            if plan:
                chunks = reviewed_chunks(plan, lines, duration, audio_hash)
            elif duration <= 180:
                chunks = [(0, duration, lines)]
            else:
                raise ValueError('Audio exceeds the aligner limit; supply reviewed chunks')
            waveform, rate = soundfile.read(str(audio), dtype='float32')
            if waveform.ndim > 1:
                waveform = waveform.mean(axis=1)
            aligned, chunk_segments, chunk_error = [], [], None
            for index, (start, end, chunk_lines) in enumerate(chunks):
                checkpoint = args.output_dir / (song + '.chunk-' + str(index) + '.json')
                key = {'audioSha256': audio_hash, 'textSha256': text_hash, 'planSha256': plan_hash, 'index': index, 'language':language}
                cached = json.loads(checkpoint.read_text(encoding='utf-8')) if checkpoint.exists() else {}
                if cached.get('key') == key:
                    words = cached['words']
                else:
                    result = model.align(audio=(waveform[int(start * rate):int(end * rate)], rate), text='\n'.join(chunk_lines),
                                         language=language)[0]
                    words = [{'text': item.text, 'start': round(start + float(item.start_time), 3),
                              'end': round(start + float(item.end_time), 3)} for item in result]
                    temporary = checkpoint.with_suffix('.json.tmp')
                    temporary.write_text(json.dumps({'key': key, 'words': words}, ensure_ascii=False), encoding='utf-8')
                    temporary.replace(checkpoint)
                aligned.extend(words)
                try:
                    chunk_segments.extend(restore_lines(chunk_lines, words))
                except ValueError as error:
                    chunk_error = str(error)
                    break
                print(json.dumps({'song': song, 'chunk': index, 'state': 'ALIGNED'}), flush=True)
            if chunk_error:
                failed = True
                print(json.dumps({'song': song, 'state': 'REVIEW', 'reason': chunk_error}), flush=True)
                continue
            raw_tmp = raw_file.with_suffix('.json.tmp')
            raw_tmp.write_text(json.dumps({'audioSha256': audio_hash, 'textSha256': text_hash, 'planSha256': plan_hash, 'language':language,
                                          'words': aligned, 'segments': chunk_segments},
                                          ensure_ascii=False, indent=2), encoding='utf-8')
            raw_tmp.replace(raw_file)
        try:
            segments = saved['segments'] if isinstance(saved, dict) and saved.get('planSha256') == plan_hash and 'segments' in saved else chunk_segments
            duration = soundfile.info(str(audio)).duration
            if any(current['start'] < previous['start'] for previous, current in zip(segments, segments[1:])):
                raise ValueError('Chunk timestamps have moved backwards')
            if segments[-1]['end'] > duration + .1:
                raise ValueError('Line exceeds audio duration')
            value = {'song': song, 'engine': 'Qwen3-ForcedAligner-0.6B', 'duration': duration, 'language':language,
                     'modelSha256': args.model_sha256, 'runtime': {'torch':torch.__version__, 'device':args.device, 'dtype':args.dtype},
                     'audioSha256': audio_hash, 'planSha256': plan_hash, 'segments': segments, 'authoredText': text}
            temporary = target.with_suffix('.json.tmp')
            temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            temporary.replace(target)
            print(json.dumps({'song': song, 'state': 'READY', 'lines': len(segments)}, ensure_ascii=True), flush=True)
        except ValueError as error:
            failed = True
            print(json.dumps({'song': song, 'state': 'REVIEW', 'reason': str(error)}, ensure_ascii=True), flush=True)
    return int(failed)

if __name__ == '__main__':
    raise SystemExit(main())
