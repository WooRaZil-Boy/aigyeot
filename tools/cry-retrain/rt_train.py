# 울음 전용 판별층 재학습·평가. 판정 단위는 기기와 같은 최근 3창(약 3초), 경보 = 3초 구간 2개 연속 기준 넘음.
# 비교: old(기존 cry_head24.json 그대로) / h24(같은 24클래스 구조, 새 데이터로 재학습) / h521(521클래스 전부)
# 평가: 묶음 5접기 교차검증(접기 0인 AxonData는 1~5 전체로 학습한 모델로 따로 시험)
import sys, json, csv, os
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score

S = sys.argv[1]
d = np.load(f"{S}/cry/rt_feats.npz", allow_pickle=True)
src, fold, cry, variant, win = d["src"], d["fold"], d["cry"].astype(bool), d["variant"], d["win"]
names = [r[2] for r in csv.reader(open(f"{S}/yamnet_classes.csv"))][1:]
idx = {n: i for i, n in enumerate(names)}
# 기존 판별층: 데이터 폴더 → 이 폴더 순서로 찾는다. 없으면 같은 클래스 구성(cry_head24_rt.json)만 쓰고 기존 모델 비교는 건너뛴다
HERE = os.path.dirname(os.path.abspath(__file__))
OLD = next((p for p in (f"{S}/cry/cry_head24.json", f"{HERE}/cry_head24.json") if os.path.exists(p)), None)
old = json.load(open(OLD or f"{HERE}/cry_head24_rt.json"))
P = old["classes"]
SIL = idx["Silence"]
VARS = ["clean", "car20", "car10", "car5", "car0", "wn0", "wn10", "wn20", "song0", "song10", "song20", "bw8"]
ALL = list(range(521))

def segs(w):
    w = w.astype(np.float32)
    if len(w) < 3: w = np.vstack([w] * 3)[:3]
    return np.stack([w[i:i + 3] for i in range(len(w) - 2)])

SEG = [segs(w) for w in win]
KEEP = [~(s[:, :, SIL] >= 0.5).all(1) for s in SEG]  # 3창 모두 무음인 구간은 학습에서 뺀다
feat = lambda s, cols: np.concatenate([s[:, :, cols].mean(1), s[:, :, cols].max(1)], 1)
F = {"24": [feat(s, P) for s in SEG], "521": [feat(s, ALL) for s in SEG]}

def fit(rows, fs, C):
    X = np.vstack([F[fs][i][KEEP[i]] for i in rows]); y = np.concatenate([[cry[i]] * KEEP[i].sum() for i in rows])
    m = LogisticRegression(C=C, max_iter=3000).fit(X, y)
    return lambda i: m.predict_proba(F[fs][i])[:, 1], m

# 기기와 같은 경보 규칙: 3초 구간 2개 연속. 구간이 하나뿐인 짧은 입력은 경보를 낼 수 없다
def alarm(p): return max(min(p[j], p[j + 1]) for j in range(len(p) - 1)) if len(p) > 1 else 0.0

w_old, b_old = np.array(old["w"]), old["bias"]
old_seg = lambda i: 1 / (1 + np.exp(-(F["24"][i] @ w_old + b_old)))
MODELS = {"old": None, "h24": ("24", 1.0), "h521": ("521", 0.02)}
if OLD is None: del MODELS["old"]

seg_p = {m: [None] * len(win) for m in MODELS}
for m, spec in MODELS.items():
    if spec is None:
        for i in range(len(win)): seg_p[m][i] = old_seg(i)
        continue
    fs, C = spec
    for k in range(1, 6):
        f, _ = fit(np.where((fold != k) & (fold != 0))[0], fs, C)
        for i in np.where(fold == k)[0]: seg_p[m][i] = f(i)
    f, mdl = fit(np.where(fold != 0)[0], fs, C)
    for i in np.where(fold == 0)[0]: seg_p[m][i] = f(i)
    if m == "h24":  # 기기용 내보내기: 기존과 같은 형식
        json.dump({"classes": P, "names": old["names"], "w": [round(float(x), 6) for x in mdl.coef_[0]], "bias": float(mdl.intercept_[0]), "threshold": 0.7,
            "feature": old["feature"], "alarm": old["alarm"], "data": "ESC-50 + Donate-a-Cry + CSD/MUSDB/LibriSpeech/ko speech negatives; DEMAND TCAR car noise, app white noise/singing playback, 8kHz band-limit"},
            open(f"{S}/cry/cry_head24_rt.json", "w"), ensure_ascii=False)
    print("trained", m, flush=True)

clip = {m: np.array([alarm(p) for p in seg_p[m]]) for m in MODELS}
os.makedirs(f"{S}/cry", exist_ok=True)
np.savez_compressed(f"{S}/cry/rt_scores.npz", **{f"seg_{m}": np.array(seg_p[m], dtype=object) for m in MODELS})

GROUPS = {"울음_ESC": (src == "esc") & cry, "울음_DAC": src == "dac", "울음_Axon(독립)": src == "axon",
    "오경보_ESC": (src == "esc") & ~cry, "오경보_말": np.char.startswith(src.astype(str), "speech"), "오경보_음악": src == "music", "오경보_노래": src == "singing"}
out = {}
for m in MODELS:
    out[m] = {}
    for v in VARS:
        vm = variant == v
        y = cry[vm & (src != "axon")]; s = clip[m][vm & (src != "axon")]
        row = {"AP": round(average_precision_score(y, s), 3)}
        for t in (0.5, 0.7, 0.9):
            row[t] = {g: f"{(clip[m][vm & gm] >= t).sum()}/{(vm & gm).sum()}" for g, gm in GROUPS.items()}
        out[m][v] = row
json.dump(out, open(f"{S}/cry/rt_results.json", "w"), ensure_ascii=False, indent=1)

for t in (0.5, 0.7, 0.9):
    print(f"\n== 기준 {t}  (울음은 재현, 오경보는 경보 낸 수)")
    print(f"{'model':5} {'var':6} {'AP':>5} " + " ".join(f"{g:>14}" for g in GROUPS))
    for m in MODELS:
        for v in VARS:
            r = out[m][v]
            print(f"{m:5} {v:6} {r['AP']:5.3f} " + " ".join(f"{r[t][g]:>14}" for g in GROUPS))
