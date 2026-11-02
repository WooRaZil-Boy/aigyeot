# ③ 달래기 실험의 성공·실패 판정이 방법(재생음)마다 얼마나 맞는지 잰다. 실제 가정 울음(AxonData, 학습에 안 씀)으로 장면을 만든다.
# 장면: 차 소음 20초 → 울음 → 차 소음. 첫 대응 재생은 울음 시작 6초 뒤(t0)부터.
#  정답 '그침': 울음이 t0 + 5~25초에 끝남 / 정답 '계속': 울음이 t0+76초까지 이어짐(짧은 파일은 이어 붙임)
# 판정 규칙
#  R1: 재생을 끄지 않고 t0+30~60초에 울음 판정이 없으면 그침(비교용 규칙)
#  R2 듣기 틈: t0+56초에 재생을 멈추고 8초(~64초) 안에 울음 판정이 없으면 '그침'
#  R3 듣기 틈 + 확인: R2에 더해, 재생을 끈 채 t0+94초까지 다시 울지 않아야 '그침'(다시 울면 실패로 고침)
import sys, glob, json, os
import numpy as np
from multiprocessing import Pool

S = sys.argv[1]; HEAD = sys.argv[2]
sys.path.insert(0, os.path.dirname(__file__))
import rt_extract as X
SR, HOP, T = 16000, 0.975, 0.7
ARMS = [("none", 0), ("wn", 10), ("brown", 10), ("song", 10), ("song", 20)]

def play_of(kind, n, rng):
    if kind == "wn": return rng.uniform(-1, 1, n).astype(np.float32)
    if kind == "brown":
        b = np.cumsum(rng.standard_normal(n)); return (b - np.convolve(b, np.ones(4000) / 4000, "same")).astype(np.float32)
    return X.song(n, 0, rng, "")

def session(k_item):
    k, (p, case, (kind, db), csnr, pause) = k_item
    rng = np.random.default_rng(k)
    cry = X.load(p)
    L = int((6 + rng.uniform(5, 25)) * SR) if case == "stop" else int(82 * SR)
    cry = cry[:L] if case == "stop" else np.resize(cry, L)
    pre = 20 * SR; t0 = pre + 6 * SR; n = t0 + 100 * SR
    noise = X.car(n, 0, rng)
    sig = np.zeros(n, np.float32); sig[pre:pre + len(cry)] = cry * X.active(noise) / X.active(cry) * 10 ** (csnr / 20)
    x = sig + noise
    if kind != "none":
        pl = play_of(kind, n - t0, rng); pl = pl / X.active(pl) * X.active(sig[pre:pre + len(cry)]) / 10 ** (db / 20)
        if pause: pl[56 * SR:] = 0  # 앱은 듣기 틈에서 재생을 멈추고, 그침이면 다시 틀지 않는다
        x[t0:] += pl
    return os.path.basename(p), case, f"{kind}{db if kind != 'none' else ''}", csnr, pause, t0 / SR, X.scores(x)

if __name__ == "__main__":
    files = sorted(glob.glob(f"{S}/data/rt/wav16/axon/*.wav"))
    jobs = [(p, case, arm, c, pause) for p in files for case in ("stop", "cont") for arm in ARMS for c in (10, 0) for pause in (False, True)]
    with Pool(10, initializer=X.init) as pool: rows = pool.map(session, enumerate(jobs))
    j = json.load(open(HEAD)); cl, w, b = j["classes"], np.array(j["w"]), j["bias"]
    res = {}
    for name, case, arm, c, pause, t0, sc in rows:
        s = sc.astype(np.float32); seg = np.stack([s[i:i + 3] for i in range(len(s) - 2)])
        p = 1 / (1 + np.exp(-(np.concatenate([seg[:, :, cl].mean(1), seg[:, :, cl].max(1)], 1) @ w + b)))
        te = (np.arange(len(p)) + 3) * HOP; ts = te - 3 * HOP  # 구간 시작·끝
        rules = {"R1_연속": not (p[(te > t0 + 30) & (te <= t0 + 60)] >= T).any()} if not pause else {
            "R2_듣기틈": not (p[(ts >= t0 + 56) & (te <= t0 + 64)] >= T).any(),
            "R3_듣기틈+확인": not (p[(ts >= t0 + 56) & (te <= t0 + 94)] >= T).any()}
        for rule, said_stop in rules.items():
            key = f"{rule} c{c}"
            res.setdefault(key, {}).setdefault(arm, {"stop": [0, 0], "cont": [0, 0]})
            r = res[key][arm][case]; r[1] += 1; r[0] += int(said_stop == (case == "stop"))
    for key in sorted(res):
        print(f"\n== {key}  (맞힌 수 / 전체)  기준 {T}")
        print(f"{'재생':8} {'정답 그침':>10} {'정답 계속':>10}")
        for arm, r in res[key].items(): print(f"{arm:8} {r['stop'][0]:>5}/{r['stop'][1]:<4} {r['cont'][0]:>5}/{r['cont'][1]:<4}")
    os.makedirs(f"{S}/cry", exist_ok=True)
    json.dump(res, open(f"{S}/cry/rt_outcome.json", "w"), ensure_ascii=False, indent=1)
