"""시안 선 아이콘(Lucide ISC + 직접 그린 4개)을 iOS 에셋 카탈로그 SVG 이미지 세트로 만든다(벡터 보존, 템플릿).
사용: python3 gen_ios_icons.py <vendor/lucide> <lucide-static/icons> <build.py> <출력 xcassets> 이름...
"""
import json, re, sys, pathlib
vendor, npm, build_py, out = map(pathlib.Path, sys.argv[1:5])
names = sys.argv[5:]
src = build_py.read_text()
custom = dict(re.findall(r'"(\w+)": \'(.*?)\',\n', src[src.index('CUSTOM = {'):src.index('def build_icons')]))
out.mkdir(parents=True, exist_ok=True)
(out / 'Contents.json').write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + '\n')
for n in names:
    if n in custom:
        inner = custom[n]
    else:
        f = vendor / f'{n}.svg'
        if not f.exists():
            f = npm / f'{n}.svg'
        body = re.sub(r'<!--.*?-->', '', f.read_text(), flags=re.S)
        inner = re.search(r'<svg[^>]*>(.*)</svg>', body, re.S).group(1)
        inner = re.sub(r'\s+', ' ', inner).strip()
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" '
           'stroke="#000000" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>\n')
    d = out / f'ic-{n}.imageset'
    d.mkdir(exist_ok=True)
    (d / f'ic-{n}.svg').write_text(svg)
    (d / 'Contents.json').write_text(json.dumps({
        "images": [{"filename": f'ic-{n}.svg', "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
        "properties": {"preserves-vector-representation": True, "template-rendering-intent": "template"},
    }, indent=2) + '\n')
print(len(names), 'icons', 'custom:', [n for n in names if n in custom])
