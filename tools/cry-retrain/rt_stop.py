# '그침' 판정 시험: 실제 가정 울음(AxonData, 학습에 안 씀)으로 주행 장면을 만들어 YAMNet + 판별층을 시간축으로 돌린다.
# 장면 = 차 소음만 20초 → 울음(파일 전체) → 차 소음만 60초. 앱 재생(없음/백색소음/브라운 잡음/노래, 울음보다 0·10·20 dB 작게)은 울음 시작 6초 뒤부터 끝까지.
# 잰 것: 첫 경보 지연, 울음 중 '그쳤다' 오판(30초 연속 음성 없음), 실제 그친 뒤 '그침' 판정까지 걸린 시간, 그친 뒤 재경보
import sys, glob, json, os
import numpy as np
from multiprocessing import Pool

S = sys.argv[1]; HEADS = sys.argv[2:]
sys.path.insert(0, os.path.dirname(__file__))
import rt_extract as X  # load/active/mix/scores/init 재사용(같은 TCAR 4채널·CSD 곡)
X.S, X.R = S, f"{S}/data/rt"
SR, HOP = 16000, 0.975
QUIET = 30  # 그침 = 울음 판정 없는 구간이 30초 연속(이 시험에서 정한 값)
COND = [(pb, db, c) for c in (10, 0) for pb, db in (("none", 0), ("wn", 10), ("wn", 20), ("brown", 10), ("brown", 0), ("song", 10), ("song", 20), ("wngap", 10), ("songgap", 10), ("songgap", 20))]  # 재생음 = 울음보다 db 작게

def session(k_item):
    k, (p, pb, db, csnr) = k_item
    rng = np.random.default_rng(k)
    cry = X.load(p)
    pre, post = 20 * SR, 60 * SR
    n = pre + len(cry) + post
    noise = X.car(n, 0, rng)
    sig = np.zeros(n, np.float32); sig[pre:pre + len(cry)] = cry * X.active(noise) / X.active(cry) * 10 ** (csnr / 20)
    x = sig + noise
    gaps = None
    if pb != "none":
        a = pre + 6 * SR
        if pb.startswith("wn"): play = rng.uniform(-1, 1, n - a).astype(np.float32)
        elif pb == "brown":  # 저주파에 몰린 '자궁 소리'형 잡음. 울음 대역(수백 Hz~4 kHz)을 덜 가린다
            b = np.cumsum(rng.standard_normal(n - a)); play = (b - np.convolve(b, np.ones(4000) / 4000, "same")).astype(np.float32)
        else: play = X.song(n - a, 0, rng, "")
        play = play / X.active(play) * X.active(sig[pre:pre + len(cry)]) / 10 ** (db / 20)
        if pb.endswith("gap"):  # 듣기 틈: 20초 틀고 4초 멈춤을 되풀이. 틈 안의 판정만 쓴다
            on_ = (np.arange(n - a) % (24 * SR)) < 20 * SR
            play = play * on_; gaps = [((a + g) / SR, (a + g) / SR + 4) for g in range(20 * SR, n - a, 24 * SR)]
        x[a:] += play
    gate = (pre + 6 * SR) / SR if pb.endswith("gap") else None
    return os.path.basename(p), f"{pb}{db if pb != 'none' else ''}", csnr, pre / SR, (pre + len(cry)) / SR, X.scores(x), (gate, gaps if gate else None)

def judge(seg, t, on, off, gate=None, gaps=None):
    tt = (np.arange(len(seg)) + 3) * HOP  # 구간 끝 시각
    pos = seg >= t
    obs = np.ones(len(seg), bool)  # 판정에 쓸 수 있는(관측한) 구간
    if gate is not None:  # 재생 시작 뒤에는 틈 안에 온전히 든 구간만 판정에 쓴다
        obs = (tt <= gate) | np.array([any(g0 <= e - 3 * HOP and e <= g1 for g0, g1 in gaps) for e in tt])
        pos = pos & obs
    al = next((tt[j + 1] for j in range(len(pos) - 1) if pos[j] and pos[j + 1] and tt[j + 1] > on), None)
    r = {"alarm": None if al is None else round(al - on, 1)}
    if al is None: return r
    run, stop = 0, None
    for j in range(len(pos)):
        if tt[j] <= al or not obs[j]: continue  # 관측하지 않은 구간은 조용한 시간에 넣지 않는다(타이머를 늘리지도 되돌리지도 않음)
        run = 0 if pos[j] else run + HOP
        if run >= QUIET: stop = tt[j]; break
    if gate is not None:  # 듣기 틈 조건: 관측 가능한 조용한 시간이 장면(최대 약 154초) 안에서 30초에 못 미쳐 구조적으로 측정 불가
        r["early_stop"] = None; r["stop_delay"] = None
    else:
        r["early_stop"] = bool(stop is not None and stop < off)  # 울음이 이어지는데 그쳤다고 판정
        r["stop_delay"] = None if stop is None else round(stop - off, 1)
    r["re_alarm"] = bool(any(pos[j] and pos[j + 1] for j in range(len(pos) - 1) if tt[j] > off + 3))  # 그친 뒤 재경보
    return r

if __name__ == "__main__":
    files = sorted(glob.glob(f"{S}/data/rt/wav16/axon/*.wav"))
    jobs = [(p, pb, db, c) for p in files for pb, db, c in COND]
    with Pool(10, initializer=X.init) as pool: rows = pool.map(session, enumerate(jobs))
    heads = {h: json.load(open(h)) for h in HEADS}
    out = {}
    for h, j in heads.items():
        cl, w, b = j["classes"], np.array(j["w"]), j["bias"]
        for t in (0.5, 0.7):
            for name, pb, c, on, off, sc, (gate, gaps) in rows:
                s = sc.astype(np.float32)
                seg = np.stack([s[i:i + 3] for i in range(len(s) - 2)])
                f = np.concatenate([seg[:, :, cl].mean(1), seg[:, :, cl].max(1)], 1)
                p = 1 / (1 + np.exp(-(f @ w + b)))
                out.setdefault(f"{os.path.basename(h)} t{t}", {}).setdefault(f"{pb}_c{c}", []).append(judge(p, t, on, off, gate, gaps))
    summ = {}
    for key, conds in out.items():
        print("\n==", key)
        print(f"{'cond':10} {'경보':>6} {'경보지연중앙':>8} {'울음중그침오판':>10} {'그침지연중앙':>8} {'그친뒤재경보':>8}")
        for cond, rs in conds.items():
            a = [r for r in rs if r["alarm"] is not None]
            na = bool(a) and all(r["early_stop"] is None for r in a)  # 듣기 틈 조건: 측정 불가
            sd = [r["stop_delay"] for r in a if r["stop_delay"] is not None and not r["early_stop"]]
            row = {"alarm": f"{len(a)}/{len(rs)}", "alarm_delay_med": float(np.median([r['alarm'] for r in a])) if a else None,
                "early_stop": "측정 불가" if na else f"{sum(r['early_stop'] for r in a)}/{len(a)}", "stop_delay_med": "측정 불가" if na else (float(np.median(sd)) if sd else None),
                "re_alarm": f"{sum(r['re_alarm'] for r in a)}/{len(a)}"}
            summ.setdefault(key, {})[cond] = row
            print(f"{cond:10} {row['alarm']:>6} {str(row['alarm_delay_med']):>8} {row['early_stop']:>10} {str(row['stop_delay_med']):>8} {row['re_alarm']:>8}")
    os.makedirs(f"{S}/cry", exist_ok=True)
    json.dump({"summary": summ, "detail": out}, open(f"{S}/cry/rt_stop.json", "w"), ensure_ascii=False, indent=1)
