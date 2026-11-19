"""Prepare version-bound lyric resources from locally recognized word timestamps.

This command has no database connection and makes no model or website requests.
Candidates must come from the exact preview audio in the recommendation manifest.
"""
import argparse, hashlib, json, math, re, statistics, uuid
from pathlib import Path

def prepare(candidate, audio, asset):
    digest = hashlib.sha256(audio.read_bytes()).hexdigest()
    if digest != asset['sha256']:
        raise ValueError('Preview audio does not match the release manifest')
    if candidate.get('song') != asset['trackId'] or candidate.get('audioSha256') != digest:
        raise ValueError('Alignment candidate belongs to a different audio version')
    words, warnings = [], []
    for segment in candidate['segments']:
        text = segment['text'].strip()
        if re.search(r'字幕(?:由|by|制作|組)|amara\.org', text, re.I):
            warnings.append('subtitle-credit-artifact-removed')
            continue
        valid = []
        for word in segment.get('words', []):
            start, end = word['start'], word['end']
            if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end > candidate['duration'] + .1:
                raise ValueError('Invalid word timestamp')
            if end <= start:
                continue  # An untimed punctuation token must not create a fake interval.
            value = word['text'].strip()
            if value:
                valid.append({'text': value, 'start': round(start, 3), 'end': round(end, 3)})
        if not valid:
            continue
        if segment.get('compression', 0) > 3 or segment.get('avgLogprob', 0) < -1.3:
            raise ValueError('Recognition needs review: uncertain or repetitive segment')
        length = 0
        for index, word in enumerate(valid):
            if words and word['start'] < words[-1]['start']:
                raise ValueError('Word timestamps are not monotonic')
            length += len(word['text'])
            next_word = valid[index + 1] if index + 1 < len(valid) else None
            boundary = (next_word is None or next_word['start'] - word['end'] >= .7
                        or re.search(r'[，,。.!！?？;；]$', word['text'])
                        or length >= (18 if re.search(r'[\u3400-\u9fff]', word['text']) else 70))
            if boundary:
                word['text'] += '\n'
                length = 0
            words.append(word)
    if len(words) < 10:
        raise ValueError('Not enough timed singing words; do not publish this candidate')
    return {
        'sourceKey': 'sample:' + asset['trackId'] + ':' + str(uuid.UUID(bytes=hashlib.md5(asset['key'].encode()).digest(), version=3)),
        'audioSha256': digest,
        'recognition': candidate.get('engine', 'offline-asr'),
        'duration': candidate['duration'],
        'warnings': warnings,
        'words': words,
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--audio-dir', type=Path, required=True)
    parser.add_argument('--candidate-dir', type=Path, required=True)
    parser.add_argument('--candidate-suffix', default='.turbo.json')
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    result = []
    for asset in json.loads(args.manifest.read_text(encoding='utf-8'))['assets']:
        if asset['kind'] != 'audio':
            continue
        source = args.candidate_dir / (asset['trackId'] + args.candidate_suffix)
        if not source.exists():
            result.append({'id': asset['trackId'], 'state': 'MISSING'})
            continue
        try:
            value = prepare(json.loads(source.read_text(encoding='utf-8')),
                            args.audio_dir / Path(asset['path']).name, asset)
            target = args.output_dir / (asset['trackId'] + '.json')
            temporary = target.with_suffix('.json.tmp')
            temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            temporary.replace(target)
            result.append({'id': asset['trackId'], 'state': 'READY', 'words': len(value['words'])})
        except ValueError as error:
            result.append({'id': asset['trackId'], 'state': 'REVIEW', 'reason': str(error)})
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if all(row['state'] == 'READY' for row in result) else 1

if __name__ == '__main__':
    raise SystemExit(main())
