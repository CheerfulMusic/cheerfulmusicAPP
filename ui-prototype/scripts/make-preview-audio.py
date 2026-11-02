"""生成原创器乐界面样曲；不调用 AI，不使用公司或第三方录音。"""
from pathlib import Path
from array import array
import math
import wave

RATE = 22050
LENGTH = 18
OUT = Path(__file__).resolve().parents[1] / 'assets' / 'audio'
OUT.mkdir(parents=True, exist_ok=True)

def frequency(note):
    return 440 * 2 ** ((note - 69) / 12)

def render(name, chords, melody, beat):
    samples = [0.0] * (RATE * LENGTH)

    def note(midi, start, length, volume, softness=1):
        hz = frequency(midi)
        for i in range(int(length * RATE)):
            at = int(start * RATE) + i
            if at >= len(samples):
                break
            t = i / RATE
            envelope = min(1, t / .025) * math.exp(-t * softness * 2.4) * min(1, (length - t) / .18)
            sound = math.sin(2 * math.pi * hz * t) + .18 * math.sin(4 * math.pi * hz * t) + .06 * math.sin(6 * math.pi * hz * t)
            samples[at] += sound * volume * envelope

    for bar in range(8):
        chord = chords[bar % len(chords)]
        start = bar * beat * 4
        note(chord[0] - 12, start, beat * 3.5, .12, .35)
        for j in range(8):
            note(chord[[0, 1, 2, 1][j % 4]], start + j * beat / 2, 1.3, .095)
        for j in range(4):
            midi = melody[(bar * 4 + j) % len(melody)]
            if midi:
                note(midi, start + j * beat + .025, beat * 1.6, .12, .8)

    peak = max(abs(s) for s in samples) or 1
    pcm = array('h')
    for i, s in enumerate(samples):
        edge = min(1, i / RATE / .25, (LENGTH - i / RATE) / 1.4)
        pcm.append(int(max(-1, min(1, s / peak * .7 * edge)) * 32767))
    with wave.open(str(OUT / f'{name}.wav'), 'wb') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(pcm.tobytes())

render('sea', [[60, 64, 67], [57, 60, 64], [53, 57, 60], [55, 59, 62]], [76, 74, 72, 0, 72, 76, 79, 76, 77, 76, 72, 69, 71, 74, 72, 0], .62)
render('home', [[62, 65, 69], [58, 62, 65], [65, 69, 72], [60, 64, 67]], [77, 76, 74, 69, 70, 74, 77, 0, 81, 79, 77, 76, 76, 72, 74, 0], .72)
render('summer', [[67, 71, 74], [64, 67, 71], [60, 64, 67], [62, 66, 69]], [79, 83, 86, 83, 79, 78, 76, 0, 76, 79, 84, 83, 81, 78, 79, 0], .48)
print('Generated three 18-second original instrumental UI samples.')
