# 에뮬레이터 확인용 10초 PCM(16kHz mono s16le)과 맥 기준값(같은 YAMNet + 새 판별층)을 만든다
import sys, os, glob, json
import numpy as np
S = sys.argv[1]; OUT = f"{S}/assets/rt"
os.makedirs(OUT, exist_ok=True)
sys.path.insert(0, os.path.dirname(__file__))
import rt_extract as X
X.init()
rng = np.random.default_rng(7); SR = 16000; N = 10 * SR
R = f"{S}/data/rt/wav16"
dac = sorted(glob.glob(f"{X.DAC}/hungry/*.wav"))[5]
src = {"axon_car10": (f"{R}/axon/cry_00012.wav", 10, None), "dac_car0": (dac, 0, None),
    "speech_car10": (sorted(glob.glob(f"{R}/zeroth/*.wav"))[3], 10, None), "song_car10": (f"{R}/csd/kr001a.wav", 10, None),
    "music_car10": (sorted(glob.glob(f"{R}/musdb/*.wav"))[10], 10, None), "cry_song10": (f"{R}/axon/cry_00029.wav", 10, "song")}
j = json.load(open(f"{S}/cry/cry_head24_rt.json")); cl, w, b = j["classes"], np.array(j["w"]), j["bias"]
ref = {}
for name, (p, snr, pb) in src.items():
    x = np.resize(X.load(p), N)
    y = X.mix(x, X.car(N, 1, rng), snr)
    if pb: sg = X.song(N, 1, rng, ""); y = y + sg / X.active(sg) * X.active(x) / 10 ** 0.5
    y = (y / max(1.0, np.abs(y).max() / 0.95) * 32767).astype("<i2")  # 넘치지 않게
    y.tofile(f"{OUT}/{name}.pcm")
    s = X.scores(y.astype(np.float32) / 32768).astype(np.float32); seg = np.stack([s[i:i + 3] for i in range(len(s) - 2)])
    pr = 1 / (1 + np.exp(-(np.concatenate([seg[:, :, cl].mean(1), seg[:, :, cl].max(1)], 1) @ w + b)))
    ref[name] = [round(float(v), 2) for v in pr]
    print("REF", name, " ".join(f"{v:.2f}" for v in pr), "alarm=", any(min(a, c) >= 0.7 for a, c in zip(pr, pr[1:])))
json.dump(ref, open(f"{OUT}/ref.json", "w"))
