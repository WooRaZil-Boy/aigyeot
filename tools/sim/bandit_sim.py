# 달래기 방법 고르기 전략 비교 시뮬레이션: 아이마다 방법 3개의 '1분 안 진정' 확률이 다르다고 가정
# 판정 오류는 오프라인 평가값을 쓴다(rt_outcome.py, Mac): 그침을 실패로 오판 0/180, 계속을 그침으로 오판 1/180(듣기 틈 + 30초 확인 R3, tools/cry-retrain/rt_outcome.json: 그침 180/180, 계속 179/180)
import numpy as np
rng = np.random.default_rng(0)
import sys
N, T, K = 20000, int(sys.argv[1]) if len(sys.argv) > 1 else 20, int(sys.argv[2]) if len(sys.argv) > 2 else 3   # 둘째 인자 = 음원 수(기본 3, 뱃속 소리 더하면 4)
if T <= 0: sys.exit('울음 횟수 T는 1 이상이어야 한다 (받은 값: %d)' % T)
P_FALSE_STOP = 1 / 180; P_FALSE_CONT = 0 / 180
def observe(true_ok):
    flip = rng.random(true_ok.shape)
    return np.where(true_ok, flip >= P_FALSE_CONT, flip < P_FALSE_STOP)
def run(p, strategy):
    n = len(p); a = np.ones((n, K)); b = np.ones((n, K)); succ = np.zeros(n); pick_best = np.zeros(n)
    pmax = p.max(1)  # 최선 방법이 여럿이면(동률) 그중 아무거나 골라도 최선으로 센다
    for t in range(T):
        if strategy == "fixed": k = np.zeros(n, int)                      # 늘 첫 방법(예: 백색소음)
        elif strategy == "random": k = rng.integers(0, K, n)
        elif strategy == "rotate": k = np.full(n, t % K)
        else:                                                              # 톰슨: 처음 2K번 돌아가며(K=3이면 6번, K=4이면 8번), 이후 베타 표본 최대
            k = np.full(n, t % K) if t < 2 * K else rng.beta(a, b).argmax(1)
        ok = rng.random(n) < p[np.arange(n), k]
        seen = observe(ok)
        a[np.arange(n), k] += seen; b[np.arange(n), k] += ~seen
        succ += ok
        if t >= T - min(T, 5): pick_best += (p[np.arange(n), k] == pmax)
    return succ.mean(), (pick_best / min(T, 5)).mean()
scen = {
    "아이마다 차이 큼(각 방법 0.1~0.9 균등)": rng.uniform(0.1, 0.9, (N, K)),
    "차이 중간(0.3~0.7)": rng.uniform(0.3, 0.7, (N, K)),
    "차이 없음(모두 0.5)": np.full((N, K), 0.5),
}
print(f"아이 {N}명 × 울음 {T}번, 방법 {K}개. 값 = {T}번 중 1분 안에 그친 평균 횟수 / 마지막 {min(T, 5)}번에서 최선 방법을 고른 비율")
for name, p in scen.items():
    oracle = p.max(1).mean() * T
    row = {s: run(p, s) for s in ("fixed", "random", "rotate", "thompson")}
    print(f"\n{name}  (최선만 쓰면 {oracle:.1f}회)")
    for s, (m, pb) in row.items(): print(f"  {s:9} 성공 {m:5.2f}회  최선 선택 {pb*100:5.1f}%")
    print(f"  톰슨 - 고정 = {row['thompson'][0] - row['fixed'][0]:+.2f}회, 톰슨 - 돌아가며 = {row['thompson'][0] - row['rotate'][0]:+.2f}회")
