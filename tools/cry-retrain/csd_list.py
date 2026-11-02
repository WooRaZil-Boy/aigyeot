# CSD.zip(1.85GB)에서 필요한 파일만 HTTP Range로 꺼낸다
import os, sys, io, zipfile, urllib.request
URL = "https://zenodo.org/api/records/4785016/files/CSD.zip/content"
class R(io.RawIOBase):
    def __init__(s):
        s.n = int(urllib.request.urlopen(urllib.request.Request(URL, headers={"Range": "bytes=0-0"})).headers["Content-Range"].split("/")[1]); s.p = 0
    def seekable(s): return True
    def readable(s): return True
    def tell(s): return s.p
    def seek(s, o, w=0): s.p = o if w == 0 else s.p + o if w == 1 else s.n + o; return s.p
    def read(s, k=-1):
        if k < 0: k = s.n - s.p
        if k == 0 or s.p >= s.n: return b""
        d = urllib.request.urlopen(urllib.request.Request(URL, headers={"Range": f"bytes={s.p}-{min(s.p + k, s.n) - 1}"})).read(); s.p += len(d); return d
    def readinto(s, b): d = s.read(len(b)); b[:len(d)] = d; return len(d)
z = zipfile.ZipFile(io.BufferedReader(R(), 1 << 20))
names = z.namelist()
if sys.argv[1] == "list":
    for i in z.infolist():
        if i.filename.endswith(".wav"): print(i.filename, round(i.file_size / 1e6, 1))
else:
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for n in sys.argv[3:]:
        open(f"{out}/{n.split('/')[-1]}", "wb").write(z.read(n)); print("got", n, flush=True)
