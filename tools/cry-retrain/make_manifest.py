# rt_feats.npz에서 특징이 나온 클립 목록(manifest.csv)을 만든다. 열: src,name,fold,cry (클립당 한 줄, 'clean' 변형 기준)
# 사용: python make_manifest.py S [출력 경로]   (기본 출력: 이 스크립트 옆 manifest.csv)
import sys, os, csv
import numpy as np
S = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest.csv")
d = np.load(f"{S}/cry/rt_feats.npz", allow_pickle=True)
m = d["variant"] == "clean"
with open(OUT, "w", newline="") as f:
    w = csv.writer(f); w.writerow(["src", "name", "fold", "cry"])
    for r in zip(d["src"][m], d["name"][m], d["fold"][m], d["cry"][m]): w.writerow([r[0], r[1], int(r[2]), int(bool(r[3]))])
print("rows", int(m.sum()), "->", OUT)
