"""
The same comparison as plc_eval.py, for Wi-Fi stalls: outages of 200 ms to 1 s
(mean burst length in packets = outage / ptime), with 15 ms jitter on top.
"""
import json
import os
from concurrent.futures import ProcessPoolExecutor

import numpy as np

import plc_eval as P


def main():
    os.makedirs(P.TMP, exist_ok=True)
    seeds = (3, 7, 11, 13, 17)
    conditions = []
    for ptime in (10, 20):
        for (loss, outage_ms) in ((0.03, 200), (0.05, 500), (0.02, 1000)):
            conditions.append((ptime, 48000, loss, outage_ms // ptime, 15))
    jobs = [c + (s, v) for c in conditions for s in seeds for v in ('expand', 'codecplc')]
    results = {}
    with ProcessPoolExecutor(max_workers=int(os.environ.get('W', '3'))) as ex:
        for job, st in ex.map(P.run, jobs):
            results[job] = st
    for c in conditions:
        e = [results[c + (s, 'expand')] for s in seeds]
        p = [results[c + (s, 'codecplc')] for s in seeds]
        diffs = [b['pesq'] - a['pesq'] for a, b in zip(e, p)]
        print(json.dumps({
            'ptime': c[0], 'loss': c[2], 'outage_ms': c[3] * c[0],
            'expand_pesq': round(float(np.mean([r['pesq'] for r in e])), 3),
            'codecplc_pesq': round(float(np.mean([r['pesq'] for r in p])), 3),
            'mean_gain': round(float(np.mean(diffs)), 3),
            'seeds_better': sum(d > 0 for d in diffs),
            'jb_ms': [round(float(np.mean([r['jb_delay_ms'] for r in e])), 1),
                      round(float(np.mean([r['jb_delay_ms'] for r in p])), 1)],
        }), flush=True)


if __name__ == '__main__':
    main()
