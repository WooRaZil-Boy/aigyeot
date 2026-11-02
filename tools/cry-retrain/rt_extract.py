# 울음 전용 재학습용 특징 추출. 에뮬레이터와 같은 YAMNet(MediaPipe yamnet.tflite)으로 0.975초 창별 521 점수를 뽑는다.
# 변형 12가지: clean, 실제 차 실내 소음(DEMAND TCAR) SNR 20/10/5/0, 차 소음 10 dB 위에 앱이 트는 백색소음·노래(CSD 한국어 동요)를
#            대상보다 0/10/20 dB 작게(wn0/wn10/wn20, song0/song10/song20), 8kHz 표본율(16k→8k→16k, 4 kHz 대역) + 차 소음 10 dB
# 접기: ESC-50은 원래 접기, 나머지는 묶음(업로더·화자·곡) 해시. 차 소음은 접기마다 다른 시간 구간(60초씩)을 쓴다.
# AxonData 가정 울음은 접기 0(학습에 안 씀, 독립 시험용)
import sys, glob, os, csv, zlib
import numpy as np, soundfile as sf
from multiprocessing import Pool
from scipy.signal import resample_poly

S = sys.argv[1]
R = f"{S}/data/rt"
ESC = f"{S}/data/ESC-50-master"
DAC = f"{S}/data/donateacry-corpus-master/donateacry_corpus_cleaned_and_updated_data"
OUT = f"{S}/cry/rt_feats.npz"
SR = 16000
VARS = ["clean", "car20", "car10", "car5", "car0", "wn0", "wn10", "wn20", "song0", "song10", "song20", "bw8"]

def load(p):
    x, sr = sf.read(p, dtype="float32", always_2d=True)
    x = x.mean(1)
    if sr != SR: g = np.gcd(sr, SR); x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    return x

def active(x):
    a = x[np.abs(x) > 1e-4]
    return np.sqrt(np.mean(a ** 2)) if len(a) else 1e-9

fold_of = lambda key: zlib.crc32(key.encode()) % 5 + 1

def items():
    out = []
    for m in csv.DictReader(open(f"{ESC}/meta/esc50.csv")):
        out.append(("esc", m["category"], int(m["fold"]), m["category"] == "crying_baby", f"{ESC}/audio/{m['filename']}", 0))
    for p in sorted(glob.glob(f"{DAC}/*/*.wav")):
        out.append(("dac", "cry", fold_of(os.path.basename(p)[:36]), True, p, 0))
    for p in sorted(glob.glob(f"{R}/wav16/zeroth/*.wav")):  # 실제 한국어 낭독(Zeroth). 화자 정보가 없어 50개씩 묶음
        out.append(("speech_ko", "speech", fold_of(os.path.basename(p)[:3]), False, p, 0))
    for p in sorted(glob.glob(f"{R}/wav16/lsmini/*.wav")):  # 영어 낭독(Mini LibriSpeech), 화자별
        out.append(("speech_en", "speech", fold_of(os.path.basename(p).split("-")[0]), False, p, 0))
    for p in sorted(glob.glob(f"{R}/wav16/say/*.wav")):  # 맥 say 한국어 합성. 소리가 나온 목소리만 쓴다(실제로는 Yuna 8개, 나머지는 0.01초 빈 파일이라 아래에서 걸러진다)
        out.append(("speech_say", "speech", fold_of(os.path.basename(p).split("_")[0]), False, p, 0))
    for p in sorted(glob.glob(f"{R}/wav16/musdb/*.wav")):
        out.append(("music", "music", fold_of(os.path.basename(p)), False, p, 0))
    for p in sorted(glob.glob(f"{R}/wav16/csd/*.wav")):  # 노래는 10초 조각
        n = int(sf.info(p).duration // 10)
        out += [("singing", "singing", fold_of(os.path.basename(p)), False, p, k) for k in range(n)]
    for p in sorted(glob.glob(f"{R}/wav16/axon/*.wav")):
        out.append(("axon", "cry", 0, True, p, 0))
    return out

clf = tcar = songs = None

def init():
    global clf, tcar, songs
    from mediapipe.tasks import python as mpt
    from mediapipe.tasks.python import audio as mpa
    clf = mpa.AudioClassifier.create_from_options(mpa.AudioClassifierOptions(
        base_options=mpt.BaseOptions(model_asset_path=f"{S}/dl/yamnet.tflite"), running_mode=mpa.RunningMode.AUDIO_CLIPS, max_results=-1))
    tcar = [load(p) for p in sorted(glob.glob(f"{R}/tcar/*/ch*.wav"))[::4]]  # 16채널 중 4개
    songs = {os.path.basename(p): (fold_of(os.path.basename(p)), load(p)) for p in sorted(glob.glob(f"{R}/wav16/csd/*.wav"))}

def scores(x):
    from mediapipe.tasks.python.components.containers import audio_data as ad
    res = clf.classify(ad.AudioData.create_from_array(x.astype(np.float32), SR))
    out = np.zeros((len(res), 521), np.float16)
    for i, r in enumerate(res):
        for c in r.classifications[0].categories: out[i, c.index] = c.score
    return out

def car(n, fold, rng):  # 접기별 60초 구간에서 자른 실제 차 실내 소음
    ch = tcar[rng.integers(len(tcar))]
    k = fold - 1 if fold else rng.integers(5)
    seg = ch[k * 60 * SR:(k + 1) * 60 * SR]
    st = rng.integers(len(seg))
    return np.resize(np.roll(seg, -st), n)

def song(n, fold, rng, avoid):
    pool = [v for k, (f, v) in songs.items() if f == fold and k != avoid] or [v for k, (f, v) in songs.items() if k != avoid]
    v = pool[rng.integers(len(pool))]
    return np.resize(np.roll(v, -rng.integers(len(v))), n)

def mix(x, noise, snr): return x + noise * active(x) / active(noise) / (10 ** (snr / 20))

def work(k_item):
    k, (src, cat, fold, cry, p, chunk) = k_item
    rng = np.random.default_rng(k)
    x = load(p)
    if src == "singing": x = x[chunk * 10 * SR:(chunk + 1) * 10 * SR]
    if len(x) < SR // 2:
        print("skip(0.5초 미만)", os.path.basename(p), flush=True); return []
    n = len(x)
    v = {"clean": x}
    for snr in (20, 10, 5, 0): v[f"car{snr}"] = mix(x, car(n, fold, rng), snr)
    wn, sg = rng.uniform(-1, 1, n).astype(np.float32), song(n, fold, rng, os.path.basename(p))
    for db in (0, 10, 20):  # 재생음 크기 = 대상(울음 등)보다 db만큼 작게
        v[f"wn{db}"] = v["car10"] + wn * active(x) / active(wn) / (10 ** (db / 20))
        v[f"song{db}"] = v["car10"] + sg * active(x) / active(sg) / (10 ** (db / 20))
    bw = resample_poly(resample_poly(x, 1, 2), 2, 1)[:n].astype(np.float32)
    v["bw8"] = mix(bw, car(n, fold, rng), 10)
    name = os.path.basename(p) + (f"#{chunk}" if src == "singing" else "")
    return [(src, cat, fold, cry, name, var, scores(v[var])) for var in VARS]

if __name__ == "__main__":
    its = items()
    print("items", len(its), flush=True)
    with Pool(10, initializer=init) as pool:
        rows = []
        for i, rs in enumerate(pool.imap(work, enumerate(its), chunksize=4)):
            rows += rs
            if i % 500 == 0: print(i, flush=True)
    cols = list(zip(*rows))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    np.savez_compressed(OUT, src=np.array(cols[0]), cat=np.array(cols[1]), fold=np.array(cols[2]), cry=np.array(cols[3]),
        name=np.array(cols[4]), variant=np.array(cols[5]), win=np.array(cols[6], dtype=object))
    print("saved", len(rows))
