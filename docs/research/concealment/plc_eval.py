"""
Does WebRTC-Audio-OpusGeneratePlc (NetEq asks the Opus decoder to conceal a gap)
sound better than NetEq's own Expand, behind 3 RED copies, as the app sends?

Each condition runs WebRTC 6367's NetEq + Opus decoder (./sim) on the same 60 s of
WebRTC's speech test recording, with the same Gilbert-Elliott loss pattern and
jitter (same seed) for both variants, and scores wideband PESQ (P.862.2) per
10 s stretch, each stretch aligned on its own. Pairs differ only in the trial.
"""
import json
import os
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor

import numpy as np
from pesq import pesq
from scipy.signal import correlate, resample_poly

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'audio', 'speech_mono_32_48kHz.pcm')
SIM = os.path.join(HERE, 'sim')
TMP = os.path.join(HERE, 'tmp')
SECONDS = 60
CHUNK_S = 10
# What the app sets (WebRtcTuning): the jitter buffer's quantile.
BASE = 'WebRTC-Audio-NetEqDelayManagerConfig/quantile:0.97/'
PLC = 'WebRTC-Audio-OpusGeneratePlc/Enabled/'

ref16 = resample_poly(np.fromfile(SRC, dtype=np.int16)[: SECONDS * 48000].astype(np.float64), 1, 3)


def score(path):
    out16 = resample_poly(np.fromfile(path, dtype=np.int16).astype(np.float64), 1, 3)
    fs = 16000
    scores = []
    for start in range(0, SECONDS - CHUNK_S + 1, CHUNK_S):
        ref = ref16[start * fs:(start + CHUNK_S) * fs]
        # The output lags the input by the network delay plus the jitter buffer, and drifts
        # a little as NetEq stretches; look for this stretch within 1.5 s after its time.
        lo = start * fs
        hi = min(len(out16), lo + CHUNK_S * fs + int(1.5 * fs))
        window = out16[lo:hi]
        probe = ref[: 4 * fs]
        c = correlate(window, probe, mode='valid', method='fft')
        k = lo + int(np.argmax(c))
        deg = out16[k:k + CHUNK_S * fs]
        if len(deg) < len(ref):
            continue
        scores.append(pesq(fs, ref, deg, 'wb'))
    return float(np.mean(scores))


def run(job):
    ptime, bitrate, loss, burst, jitter, seed, variant = job
    p_bg = 1.0 / burst
    p_gb = loss * p_bg / (1 - loss)
    trials = BASE + (PLC if variant == 'codecplc' else '')
    out = os.path.join(TMP, f'{variant}_{ptime}_{bitrate}_{loss}_{burst}_{jitter}_{seed}.pcm')
    # fec=1 and the loss percentage the encoder would hear about, as WebRTC configures Opus.
    st = json.loads(subprocess.check_output([
        SIM, SRC, str(SECONDS), str(bitrate), str(ptime), '1', str(round(loss * 100)), '3',
        str(p_gb), str(p_bg), str(seed), str(jitter), trials, out]))
    st['pesq'] = score(out)
    os.remove(out)
    return job, st


def main():
    os.makedirs(TMP, exist_ok=True)
    seeds = (3, 7, 11, 13, 17)
    conditions = [
        (ptime, bitrate, loss, burst, jitter)
        for ptime in (10, 20)
        for bitrate in (48000, 20000)
        for (loss, burst) in ((0.03, 2), (0.05, 4), (0.10, 4), (0.10, 8))
        for jitter in (0, 15)
    ]
    jobs = [c + (s, v) for c in conditions for s in seeds for v in ('expand', 'codecplc')]
    results = {}
    with ProcessPoolExecutor(max_workers=int(os.environ.get('W', '3'))) as ex:
        for job, st in ex.map(run, jobs):
            results[job] = st
    out = []
    for c in conditions:
        e = [results[c + (s, 'expand')] for s in seeds]
        p = [results[c + (s, 'codecplc')] for s in seeds]
        diffs = [b['pesq'] - a['pesq'] for a, b in zip(e, p)]
        row = {
            'ptime': c[0], 'bitrate': c[1], 'loss': c[2], 'burst': c[3], 'jitter': c[4],
            'expand_pesq': round(float(np.mean([r['pesq'] for r in e])), 3),
            'codecplc_pesq': round(float(np.mean([r['pesq'] for r in p])), 3),
            'mean_gain': round(float(np.mean(diffs)), 3),
            'seeds_better': sum(d > 0 for d in diffs),
            'concealed_pct': round(100 * sum(r['concealed'] for r in e) / sum(r['total'] for r in e), 2),
            'jb_ms': [round(float(np.mean([r['jb_delay_ms'] for r in e])), 1),
                      round(float(np.mean([r['jb_delay_ms'] for r in p])), 1)],
        }
        out.append(row)
        print(json.dumps(row), flush=True)
    gains = [r['mean_gain'] for r in out]
    print(json.dumps({'conditions': len(out), 'better_in': sum(g > 0 for g in gains),
                      'mean_gain': round(float(np.mean(gains)), 3), 'min_gain': min(gains), 'max_gain': max(gains)}))


if __name__ == '__main__':
    main()
