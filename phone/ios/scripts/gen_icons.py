"""시안 선 아이콘(Lucide ISC + 직접 그린 4개 + 기록 시트 v8 그림 7개)을 iOS 에셋 카탈로그 SVG 이미지 세트로 만든다(벡터 보존, 템플릿).
사용: python3 gen_ios_icons.py <vendor/lucide> <lucide-static/icons> <build.py> <출력 xcassets> 이름...
"""
import json, re, sys, pathlib
vendor, npm, build_py, out = map(pathlib.Path, sys.argv[1:5])
names = sys.argv[5:]
src = build_py.read_text()
custom = dict(re.findall(r'"(\w+)": \'(.*?)\',\n', src[src.index('CUSTOM = {'):src.index('def build_icons')]))
# 기록 시트 v8(시안 js/sheet.js ICONS): Lucide에 없는 쪽·기저귀 종류 그림. 이 그림들과 빈 상태 달력은 선 1.75(시안 .oseg·.ring svg)
DROP = 'M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z'
POO = ''.join(f'<path d="{d}"/>' for d in ['M6.3 20.5h11.4a2.75 2.75 0 0 0 0-5.5H6.3a2.75 2.75 0 0 0 0 5.5z',
    'M6.6 15a2.3 2.3 0 0 1 1.9-4.5h7a2.3 2.3 0 0 1 1.9 4.5', 'M9.2 10.5c0-2.6 1.6-4.2 4.6-5 -.4 1.3.1 2.3 1 2.9.7.5.8 1.3.5 2.1'])
V8 = {
    'side-left': '<circle cx="7" cy="12" r="4.5" fill="#000000"/><circle cx="17" cy="12" r="4.5"/>',
    'side-right': '<circle cx="7" cy="12" r="4.5"/><circle cx="17" cy="12" r="4.5" fill="#000000"/>',
    'side-both': '<circle cx="7" cy="12" r="4.5" fill="#000000"/><circle cx="17" cy="12" r="4.5" fill="#000000"/>',
    'droplet': f'<path d="{DROP}"/>',
    'poo': POO,
    # vector-effect(non-scaling-stroke)를 쓰지 않고 줄인 배율만큼 선을 굵게 해 같은 1.75로 보이게 한다
    'pee-poo': f'<g transform="translate(.5 1.5) scale(.6)" stroke-width="{1.75 / 0.6:.3f}"><path d="{DROP}"/></g>'
               f'<g transform="translate(7.6 7.4) scale(.68)" stroke-width="{1.75 / 0.68:.3f}">{POO}</g>',
    'clean': '<path d="M11 4c.7 4 2.3 5.6 6.2 6.3-3.9.7-5.5 2.3-6.2 6.2-.7-3.9-2.3-5.5-6.2-6.2C8.7 9.6 10.3 8 11 4z"/><path d="M19 15v5"/><path d="M16.5 17.5h5"/>',
}
custom.update(V8)
stroke = {n: 1.75 for n in list(V8) + ['calendar']}
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
           f'stroke="#000000" stroke-width="{stroke.get(n, 2)}" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>\n')
    d = out / f'ic-{n}.imageset'
    d.mkdir(exist_ok=True)
    (d / f'ic-{n}.svg').write_text(svg)
    (d / 'Contents.json').write_text(json.dumps({
        "images": [{"filename": f'ic-{n}.svg', "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
        "properties": {"preserves-vector-representation": True, "template-rendering-intent": "template"},
    }, indent=2) + '\n')
print(len(names), 'icons', 'custom:', [n for n in names if n in custom])
