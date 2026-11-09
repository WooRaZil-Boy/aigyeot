#!/usr/bin/env python3
"""폰 앱 아이콘 자원을 만든다(디자인 v6).

1) 선 아이콘: Lucide SVG(ISC) + 직접 그린 4개(젖병·기저귀·부모 노래·카시트) → res/drawable/ic_*.xml(VectorDrawable).
   Lucide의 circle·rect·line·polyline·polygon·ellipse는 path로 바꾼다. 선 2, 둥근 끝·이음, 24 칸 그대로.
2) 앱 아이콘: 앞 층 SVG(초승달 + 아이 점) → 적응형 앞 층·단색(monochrome)·알림 작은 아이콘(흰 실루엣).
   적응형 앞 층은 시안 시스템 탭 '안드로이드 · 원'(1024 원본을 원으로 자름)과 같게, 원본 전체를 런처가 보이는
   지름 72dp(108dp 중)에 맞춘다: 72/108 = 0.667배. 표지의 가장 먼 점(중심에서 318.6/1024)이 212가 되어 안전 원(지름 66dp = 반지름 313) 안이다.

사용:
  python3 phone/android/scripts/icons.py <lucide 폴더> <앞 층 SVG> phone/android/src/main/res
"""
import math
import re
import sys
from pathlib import Path

# 화면에서 쓰는 아이콘만 넣는다. 더 필요하면 이름을 더하고 다시 돌린다.
NAMES = [
    "arrow-left", "baby", "bell", "car-front", "check", "chevron-down", "chevron-right", "circle-check", "clock",
    "download", "file-up", "heart-pulse", "house", "info", "link", "list", "locate-fixed", "lock", "mic", "minus",
    "moon", "moon-star", "notebook-pen", "pause", "play", "plus", "qr-code", "rotate-3d", "route", "settings",
    "share", "shield-check", "smartphone", "sparkles", "square", "trash-2", "unlink", "users", "waves", "x",
]

# 직접 그린 4개(시안 scripts/build.py CUSTOM과 같은 글자)
CUSTOM = {
    "bottle": '<path d="M9 2h6"/><path d="M10 2v3.2L8.4 7.4A3 3 0 0 0 8 9v10a3 3 0 0 0 3 3h2a3 3 0 0 0 3-3V9a3 3 0 0 0-.4-1.6L14 5.2V2"/><path d="M8 12h3"/><path d="M8 16h3"/>',
    "diaper": '<path d="M3 6h18v3a9 9 0 0 1-6.2 8.6L14 18h-4l-.8-.4A9 9 0 0 1 3 9z"/><path d="M3 9c2.5 0 4 1.5 5 4"/><path d="M21 9c-2.5 0-4 1.5-5 4"/>',
    "song": '<path d="M9 2a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/><path d="M15 10a6 6 0 0 1-12 0"/><path d="M9 16v4"/><path d="M19.6 13.8c-.9.9-2.6 2.3-2.6 2.3s-1.7-1.4-2.6-2.3a1.5 1.5 0 0 1 2.1-2.1l.5.5.5-.5a1.5 1.5 0 0 1 2.1 2.1z"/>',
    "carseat": '<path d="M4 15a8 8 0 0 0 16 0z"/><path d="M5.5 15a6.5 6.5 0 0 1 13 0"/><path d="M7 19.5 5.5 22"/><path d="M17 19.5 18.5 22"/>',
}


def attrs(tag):
    return dict(re.findall(r'([\w-]+)="([^"]*)"', tag))


def f(v):
    return float(v) if v else 0.0


def n(x):
    return f"{x:g}"


def to_path(tag):
    """SVG 도형 하나 → path d."""
    name = re.match(r"<\s*(\w+)", tag).group(1)
    a = attrs(tag)
    if name == "path":
        return a["d"]
    if name in ("circle", "ellipse"):
        cx, cy = f(a.get("cx")), f(a.get("cy"))
        rx = f(a.get("r") or a.get("rx"))
        ry = f(a.get("r") or a.get("ry"))
        return f"M{n(cx - rx)} {n(cy)}a{n(rx)} {n(ry)} 0 1 0 {n(2 * rx)} 0a{n(rx)} {n(ry)} 0 1 0 {n(-2 * rx)} 0"
    if name == "rect":
        x, y, w, h = f(a.get("x")), f(a.get("y")), f(a["width"]), f(a["height"])
        rx = f(a.get("rx") or a.get("ry"))
        ry = f(a.get("ry") or a.get("rx"))
        if not rx:
            return f"M{n(x)} {n(y)}h{n(w)}v{n(h)}h{n(-w)}z"
        return (f"M{n(x + rx)} {n(y)}h{n(w - 2 * rx)}a{n(rx)} {n(ry)} 0 0 1 {n(rx)} {n(ry)}v{n(h - 2 * ry)}"
                f"a{n(rx)} {n(ry)} 0 0 1 {n(-rx)} {n(ry)}h{n(-(w - 2 * rx))}a{n(rx)} {n(ry)} 0 0 1 {n(-rx)} {n(-ry)}"
                f"v{n(-(h - 2 * ry))}a{n(rx)} {n(ry)} 0 0 1 {n(rx)} {n(-ry)}z")
    if name == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if name in ("polyline", "polygon"):
        pts = re.split(r"[\s,]+", a["points"].strip())
        xy = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(xy) + ("z" if name == "polygon" else "")
    raise SystemExit(f"모르는 도형: {name}")


def vector(body):
    tags = re.findall(r"<(?:path|circle|ellipse|rect|line|polyline|polygon)\b[^>]*>", body)
    paths = "\n".join(
        f'    <path android:pathData="{to_path(t)}" android:fillColor="#00000000" android:strokeColor="#FF000000" '
        f'android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />'
        for t in tags
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- 생성 파일(scripts/icons.py). Lucide(ISC, assets/licenses/ISC-lucide.txt) 또는 아이곁 직접 그림. 색은 쓰는 곳에서 입힌다. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
        'android:viewportWidth="24" android:viewportHeight="24">\n'
        f"{paths}\n</vector>\n"
    )


def app_icon(fg_svg, res):
    src = Path(fg_svg).read_text()
    moon = re.search(r'id="moon"[^>]*\sd="([^"]+)"', src).group(1)
    c = attrs(re.search(r'<circle id="child"[^>]*>', src).group(0))
    tx, ty = map(float, re.search(r'translate\(([-\d.]+) ([-\d.]+)\)', src).groups())
    stops = re.findall(r'stop offset="([\d.]+)" stop-color="(#[0-9A-Fa-f]{6})"', src)
    cx, cy, r = f(c["cx"]), f(c["cy"]), f(c["r"])
    dot = f"M{n(cx - r)} {n(cy)}a{n(r)} {n(r)} 0 1 0 {n(2 * r)} 0a{n(r)} {n(r)} 0 1 0 {n(-2 * r)} 0"
    # 초승달 상자(그러데이션은 objectBoundingBox 세로 0→1): 필렛 호까지 넣은 실제 path의 위아래.
    top, bot = moon_bounds(moon)
    g = "".join(f'<item android:offset="{o}" android:color="#FF{col[1:]}" />' for o, col in stops)
    head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 생성 파일(scripts/icons.py) ← 시안 assets/brand/app-icon-fg.svg -->\n'

    def doc(size, scale, moon_fill, dot_fill, gradient):
        pivot = 512
        fill = (
            '<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" '
            f'android:startY="{n(top)}" android:endX="0" android:endY="{n(bot)}">{g}</gradient></aapt:attr>'
            if gradient else ""
        )
        mf = "" if gradient else f' android:fillColor="{moon_fill}"'
        return (
            head
            + '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" '
            f'android:width="{size}dp" android:height="{size}dp" android:viewportWidth="1024" android:viewportHeight="1024">\n'
            f'  <group android:pivotX="{pivot}" android:pivotY="{pivot}" android:scaleX="{scale}" android:scaleY="{scale}">\n'
            f'    <group android:translateX="{n(tx)}" android:translateY="{n(ty)}">\n'
            f'      <path android:pathData="{moon}"{mf}>{fill}</path>\n'
            f'      <path android:pathData="{dot}" android:fillColor="{dot_fill}" />\n'
            "    </group>\n  </group>\n</vector>\n"
        )

    d = Path(res) / "drawable"
    (d / "ic_launcher_foreground.xml").write_text(doc(108, 0.667, None, "#FFFF8A4C", True))
    (d / "ic_launcher_monochrome.xml").write_text(doc(108, 0.667, "#FF000000", "#FF000000", False))
    # 알림 작은 아이콘: 시스템이 알파만 쓴다(흰 실루엣). 24dp 칸을 꽉 채우게 1.45배.
    (d / "ic_stat_aigyeot.xml").write_text(doc(24, 1.45, "#FFFFFFFF", "#FFFFFFFF", False))


def moon_bounds(d):
    """초승달 path의 실제 위·아래 끝(필렛 호 포함). 그러데이션 objectBoundingBox 세로 0→1이 이 두 값이다.
    path는 M 다음 A(호)만 있다: 끝점 표기 호를 중심 표기로 바꿔 표본으로 잰다(SVG 1.1 부록 F.6.5)."""
    nums = [float(x) for x in re.findall(r"-?[\d.]+", d)]
    x0, y0 = nums[0], nums[1]
    ys = [y0]
    i = 2
    while i + 7 <= len(nums):
        rx, ry, _, large, sweep, x1, y1 = nums[i:i + 7]
        i += 7
        dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
        lam = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        if lam > 1:
            rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
        num = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx
        k = math.sqrt(max(0.0, num / (rx * rx * dy * dy + ry * ry * dx * dx)))
        if large == sweep:
            k = -k
        cx, cy = k * rx * dy / ry + (x0 + x1) / 2, -k * ry * dx / rx + (y0 + y1) / 2
        t0 = math.atan2((y0 - cy) / ry, (x0 - cx) / rx)
        t1 = math.atan2((y1 - cy) / ry, (x1 - cx) / rx)
        dt = t1 - t0
        if sweep and dt < 0:
            dt += 2 * math.pi
        if not sweep and dt > 0:
            dt -= 2 * math.pi
        ys += [cy + ry * math.sin(t0 + dt * j / 2000) for j in range(2001)]
        x0, y0 = x1, y1
    return round(min(ys), 2), round(max(ys), 2)


def main(lucide, fg, res):
    d = Path(res) / "drawable"
    d.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        body = (Path(lucide) / f"{name}.svg").read_text()
        (d / f"ic_{name.replace('-', '_')}.xml").write_text(vector(body))
    for name, body in CUSTOM.items():
        (d / f"ic_{name}.xml").write_text(vector(body))
    app_icon(fg, res)


if __name__ == "__main__":
    main(*sys.argv[1:4])
