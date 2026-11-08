#!/usr/bin/env python3
"""뱃속 소리(음원 후보 C118) 합성: 심장 박동 72회/분 + 저역 통과한 '쉬' 소리, 60초 반복용.

python3 tools/sound/womb.py          # app/src/main/assets/sound/womb.m4a 생성
python3 tools/sound/womb.py --check  # 만든 m4a를 두 번 이어 붙여 반복 이음새 확인
numpy와 ffmpeg/ffprobe만 쓴다. 고정 시드라 WAV는 항상 같다.
"""
import os, re, subprocess, sys, tempfile, wave
import numpy as np

SR, DUR, BPM = 44100, 60, 72
TARGET_LUFS = -30.0
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app/src/main/assets/sound/womb.m4a")
N = SR * DUR


def thump(freq, amp):
    """짧은 어택(8ms)과 감쇠(약 70ms)를 가진 낮은 소리. 기본음은 살짝 내려가고 배음은 약하게 둔다."""
    t = np.arange(int(0.4 * SR)) / SR
    f = freq * (1 + 0.25 * np.exp(-t / 0.04))            # 내려가는 음높이
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) + 0.35 * np.sin(2 * ph) + 0.2 * np.sin(3 * ph)  # 배음: 차 스피커용 100~200Hz
    env = np.minimum(t / 0.008, 1) * np.exp(-t / 0.07)
    return amp * body * env


def synth():
    rng = np.random.default_rng(118)
    # '쉬': 흰잡음을 FFT에서 저역 통과(700Hz 위로 12dB/oct) + 핑크 기울기. 원형 필터라 처음과 끝이 이어진다.
    spec = np.fft.rfft(rng.standard_normal(N))
    f = np.fft.rfftfreq(N, 1 / SR)
    spec *= 1 / (1 + (f / 700) ** 2) / np.sqrt(np.maximum(f, 30) / 30)
    spec[f < 20] = 0
    shh = np.fft.irfft(spec, N)
    shh /= np.std(shh)
    t = np.arange(N) / SR
    shh *= 1 + 0.15 * np.sin(2 * np.pi * BPM / 60 * t)   # 박동에 맞춘 혈류 느낌, 60초에 정확히 72주기
    # 박동: 박마다 쿵(55Hz) - 쿵(65Hz, 약하게, 0.3초 뒤). 끝을 넘는 꼬리는 처음으로 이어 붙인다.
    beat = np.zeros(N)
    for k in range(BPM * DUR // 60):
        for dt, fr, am in ((0, 55, 1.0), (0.3, 65, 0.55)):
            s = thump(fr, am)
            i = int((0.25 + k * 60 / BPM + dt) * SR)  # 0.25초 비껴 시작: 반복 이음새가 박 사이 빈 곳에 놓인다
            idx = (i + np.arange(len(s))) % N
            beat[idx] += s
    return 0.45 * shh + 1.2 * beat


def wav(path, x, gain):
    y = np.clip(x * gain, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype("<i2").tobytes())


def meter(path):
    r = subprocess.run(["ffmpeg", "-nostats", "-i", path, "-af", "ebur128=peak=true", "-f", "null", "-"],
                       capture_output=True, text=True).stderr.split("Summary:")[-1]
    return float(re.search(r"I:\s+(-?[\d.]+) LUFS", r).group(1)), float(re.search(r"Peak:\s+(-?[\d.]+) dBFS", r).group(1))


def encode(w, out):
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", w, "-c:a", "aac", "-b:a", "64k", "-ac", "1", "-ar", "44100",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", out], check=True)


def build():
    x = synth()
    x /= np.max(np.abs(x))
    g = 10 ** (-12.5 / 20)
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, "womb.wav")
        for _ in range(3):  # 재서 맞추고, 인코딩 뒤 m4a로 다시 재서 맞춘다
            wav(w, x, g)
            lufs, _ = meter(w)
            g *= 10 ** ((TARGET_LUFS - lufs) / 20)
        for _ in range(2):
            wav(w, x, g)
            encode(w, OUT)
            lufs, _ = meter(OUT)
            if abs(lufs - TARGET_LUFS) <= 0.05:
                break
            g *= 10 ** ((TARGET_LUFS - lufs) / 20)
    print(f"gain={20*np.log10(g):.2f} dB (정규화 정점 기준)")


def probe():
    print(subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                          "stream=codec_name,profile,channels,sample_rate,bit_rate:format=duration,size", OUT],
                         capture_output=True, text=True).stdout)
    lufs, peak = meter(OUT)
    print(f"ebur128: I = {lufs} LUFS, Peak = {peak} dBFS, 크기 = {os.path.getsize(OUT)} 바이트")
    assert abs(lufs - TARGET_LUFS) <= 0.2 and peak < -1 and os.path.getsize(OUT) <= 1_000_000


def check():
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", OUT, "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True).stdout
    a = np.frombuffer(pcm, "<i2").astype(float) / 32768
    x = np.concatenate([a, a]); s = len(a); w = int(0.02 * SR)
    rms = lambda v: float(np.sqrt(np.mean(v ** 2)))
    mean = rms(a)
    before, after = rms(x[s - w:s]), rms(x[s:s + w])
    jump = abs(x[s] - x[s - 1])
    maxd = float(np.max(np.abs(np.diff(a))))
    print(f"길이 {len(a)/SR:.3f}초, 평균 RMS {mean:.4f}, 이음새 앞 20ms {before:.4f}, 뒤 20ms {after:.4f}")
    print(f"이음새 표본 차이 {jump:.4f}, 파일 안 최대 표본 차이 {maxd:.4f}")
    ok = before <= 2 * mean and after <= 2 * mean and jump <= maxd
    print("이음새:", "통과" if ok else "실패")
    print("참고: AAC 앞뒤 패딩 때문에 MediaPlayer 반복에서는 짧은 틈이 생길 수 있다(여기선 디코더가 패딩을 뗀 PCM 기준).")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
    else:
        build(); probe()
