#!/usr/bin/env python3
"""폰 앱 '정보·라이선스' 목록을 만든다.

release 런타임 의존성마다 POM의 <licenses> 이름·주소를 그대로 가져온다(부모 POM까지 따라간다).
사용:
  ./gradlew :phone:dependencies --configuration releaseRuntimeClasspath -q > deps.txt
  python3 phone/android/scripts/oss_licenses.py deps.txt phone/android/src/main/assets/licenses/oss.json
전문 파일: 이름에 Apache가 있으면 Apache-2.0.txt, BSD-3-Clause면 BSD-3-Clause-protobuf.txt. 그 밖(Google 약관)은 주소만 보인다.
Maven 밖에서 앱에 넣은 자산(서체·선 아이콘)은 EXTRA에 적어 함께 쓴다.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from glob import glob

NS = "{http://maven.apache.org/POM/4.0.0}"
ROOT = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")


def find(e, tag):
    x = e.find(NS + tag)
    return x if x is not None else e.find(tag)


def text(e, tag):
    x = find(e, tag)
    return (x.text or "").strip() if x is not None else ""


def pom(g, a, v):
    f = glob(f"{ROOT}/{g}/{a}/{v}/*/{a}-{v}.pom")
    return ET.parse(f[0]).getroot() if f else None


def licenses(g, a, v, depth=0):
    p = pom(g, a, v)
    if p is None:
        raise SystemExit(f"POM 없음: {g}:{a}:{v}")
    ls = find(p, "licenses")
    if ls is not None and len(ls):
        return [(text(l, "name"), text(l, "url")) for l in ls]
    par = find(p, "parent")
    if par is not None and depth < 5:
        return licenses(text(par, "groupId"), text(par, "artifactId"), text(par, "version"), depth + 1)
    raise SystemExit(f"라이선스 없음: {g}:{a}:{v}")


# Maven 의존성이 아닌 앱 자산: res/font의 Pretendard, res/drawable의 Lucide 선 아이콘.
EXTRA = [
    {"name": "Pretendard", "artifact": "pretendard@1.3.9 (res/font/pretendard_variable.ttf)",
     "licenses": [{"name": "SIL Open Font License 1.1", "url": "https://openfontlicense.org", "file": "OFL-1.1-pretendard.txt"}]},
    {"name": "Lucide", "artifact": "lucide-static@1.70.0 (res/drawable/ic_*.xml)",
     "licenses": [{"name": "ISC License", "url": "https://lucide.dev/license", "file": "ISC-lucide.txt"}]},
]


def full_text(name):
    if "Apache" in name:
        return "Apache-2.0.txt"
    if name == "BSD-3-Clause":
        return "BSD-3-Clause-protobuf.txt"
    return None


def main(deps, out):
    mods = {}
    for line in open(deps, encoding="utf-8"):
        if "project :" in line:
            continue
        m = re.search(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
        if m:
            g, a, v, v2 = m.groups()
            mods[(g, a)] = v2 or v
    # 멀티플랫폼 뿌리 모듈(ui)과 안드로이드 모듈(ui-android)이 함께 있으면 실제 코드인 쪽만 남긴다.
    keep = {k: v for k, v in mods.items() if (k[0], k[1] + "-android") not in mods and (k[0], k[1] + "-jvm") not in mods}
    rows = []
    for (g, a), v in sorted(keep.items()):
        p = pom(g, a, v)
        name = text(p, "name") or a
        rows.append({
            "name": name,
            "artifact": f"{g}:{a}:{v}",
            "licenses": [{"name": n, "url": u, "file": full_text(n)} for n, u in licenses(g, a, v)],
        })
    rows += EXTRA
    rows.sort(key=lambda r: r["name"].lower())
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{len(rows)}개 → {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
