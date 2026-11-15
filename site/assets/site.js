(function () {
  'use strict';
  document.documentElement.classList.add('js');
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.prototype.slice.call((el || document).querySelectorAll(s));
  const num = n => n.toLocaleString('ko-KR');
  const f1 = v => v.toFixed(1), f2 = v => v.toFixed(2);
  const GH = 'https://github.com/WooRaZil-Boy/aigyeot/blob/main/';

  /* ---------- 스크립트는 같은 묶음을 한 번만 읽는다 ---------- */
  const loading = {};
  function loadOnce(list) {
    const key = list.join('|');
    if (!loading[key]) loading[key] = new Promise((res, rej) => {
      (function next(i) {
        if (i >= list.length) { res(); return; }
        const s = document.createElement('script');
        s.src = list[i];
        s.onload = () => next(i + 1);
        s.onerror = rej;
        document.head.appendChild(s);
      })(0);
    });
    return loading[key];
  }
  const afterLoad = f => { if (document.readyState === 'complete') f(); else window.addEventListener('load', f); };

  /* ---------- 메뉴(해시 주소는 기획서 링크가 걸려 있어 바꾸지 않는다) ---------- */
  const pages = $$('section[data-page]');
  const links = $$('nav a');
  const frame = $('.design-frame');
  const onShow = [];
  function fit() {
    // 시안 창을 화면 남은 높이에 맞춘다. 바깥에 스크롤할 여지가 없어야 시안 안의 3D·포커스가 바깥을 끌어내리지 않는다
    if (document.body.dataset.page !== 'design') return;
    const top = frame.getBoundingClientRect().top + window.scrollY;
    frame.style.height = Math.max(420, window.innerHeight - top - 20) + 'px';
  }
  // 해시는 세 가지다: 메뉴 id(그 메뉴를 열고 맨 위로), 메뉴 안 절 id(그 메뉴를 열고 그 절로), 메뉴 밖 요소(지금 메뉴를 두고 그 요소에 포커스, 예: 본문으로 건너뛰기)
  let anchor = null;
  const dropAnchor = () => { anchor = null; };  // 사용자가 직접 움직이거나 다른 해시로 가면 풀린다
  ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(t => window.addEventListener(t, dropAnchor, { passive: true }));
  // 바로가기로 연 절은 글꼴 조각과 그림이 늦게 와 높이가 바뀔 때마다 다시 맞추고, 모두 정해진 뒤(그림을 다시 그리는 틈까지) 앵커를 푼다
  let settleT = 0;
  function settleAnchor(final) {
    if (!anchor) return;
    anchor.scrollIntoView();
    if (final && document.fonts.status === 'loaded' && (plotOK || document.body.dataset.page !== 'results')) anchor = null;
  }
  document.fonts.addEventListener('loadingdone', () => settleAnchor(true));
  function show() {
    let h = (location.hash || '').slice(1);
    try { h = decodeURIComponent(h); } catch (e) { /* 그대로 쓴다 */ }
    let id = h || 'overview', target = null, outside = false;
    if (!pages.some(p => p.dataset.page === id)) {
      target = document.getElementById(h);
      const sec = target && target.closest('section[data-page]');
      if (sec) id = sec.dataset.page;
      else if (target) { id = document.body.dataset.page || 'overview'; outside = true; }
      else id = 'overview';
    }
    document.body.dataset.page = id;
    pages.forEach(p => p.classList.toggle('on', p.dataset.page === id));
    links.forEach(a => { if (a.getAttribute('href') === '#' + id) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
    if (id === 'design' && !frame.src) frame.src = frame.dataset.src;  // 시안은 3D 모델이 커서 메뉴를 열 때만 읽는다
    onShow.forEach(f => f(id));
    anchor = null;
    if (outside) target.focus();
    else if (target) { anchor = target; target.scrollIntoView(); }  // 그림이 늦게 그려져 높이가 바뀌면 anchor로 다시 맞춘다
    else window.scrollTo(0, 0);
    fit();
  }
  // 지금 보는 메뉴를 다시 누르면 주소가 같아 hashchange가 나지 않으므로 맨 위로 직접 보낸다
  links.forEach(a => a.addEventListener('click', () => { if (a.getAttribute('href') === location.hash) { window.scrollTo(0, 0); fit(); } }));
  frame.addEventListener('load', () => { if (document.body.dataset.page === 'design') window.scrollTo(0, 0); });
  window.addEventListener('resize', fit);
  window.addEventListener('hashchange', show);

  /* ---------- 라디오 묶음(화살표 키로 고름) ---------- */
  function radios(group, onPick) {
    const bs = $$('[role="radio"]', group);
    function pick(b, focus) {
      bs.forEach(x => { const on = x === b; x.setAttribute('aria-checked', on); x.tabIndex = on ? 0 : -1; });
      if (focus) b.focus();
      onPick(b);
    }
    bs.forEach(b => { b.tabIndex = b.getAttribute('aria-checked') === 'true' ? 0 : -1; b.addEventListener('click', () => pick(b)); });
    group.addEventListener('keydown', e => {
      const i = bs.indexOf(document.activeElement);
      const d = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
      if (i < 0 || !d) return;
      e.preventDefault();
      pick(bs[(i + d + bs.length) % bs.length], true);
    });
    return pick;
  }
  // 목록형 탭: 화살표·Home·End로 옮기고 고른다
  function tabKeys(list, count, current, go) {
    list.addEventListener('keydown', e => {
      const n = count(), at = current();
      const to = { ArrowRight: at + 1, ArrowDown: at + 1, ArrowLeft: at - 1, ArrowUp: at - 1, Home: 0, End: n - 1 }[e.key];
      if (to === undefined) return;
      e.preventDefault();
      go((to + n) % n);
    });
  }

  /* ---------- 값 풍선: data-tip은 마우스·키보드, data-ktip은 키보드만(그림 라이브러리 풍선과 겹치지 않게) ---------- */
  const tip = $('#tip');
  let tipEl = null;
  function showTip(el, text, x, y) {
    tipEl = el;
    tip.textContent = text;
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    tip.style.left = Math.min(Math.max(8, x - w / 2), window.innerWidth - w - 8) + 'px';
    tip.style.top = (y - h - 12 < 8 ? y + 18 : y - h - 12) + 'px';
  }
  function hideTip() { tip.hidden = true; tipEl = null; }
  document.addEventListener('pointermove', e => {
    if (e.pointerType === 'touch') return;
    const el = e.target.closest && e.target.closest('[data-tip]');
    if (el) showTip(el, el.dataset.tip, e.clientX, e.clientY);
    else if (tipEl && tipEl !== document.activeElement) hideTip();
  });
  document.addEventListener('pointerdown', e => {
    const el = e.target.closest && e.target.closest('[data-tip]');
    if (e.pointerType === 'touch' && el) showTip(el, el.dataset.tip, e.clientX, e.clientY); else if (!el) hideTip();
  });
  document.addEventListener('focusin', e => {
    const el = e.target.closest && e.target.closest('[data-tip],[data-ktip]');
    if (el) { const r = el.getBoundingClientRect(); showTip(el, el.dataset.tip || el.dataset.ktip, r.left + r.width / 2, r.top); } else hideTip();
  });
  document.addEventListener('focusout', () => { if (tipEl !== document.activeElement) hideTip(); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') hideTip(); });
  window.addEventListener('scroll', () => {
    if (tip.hidden) return;
    // 키보드로 닿은 항목은 Tab이 화면 안으로 끌어올리는 스크롤에 풍선이 꺼지지 않게 자리만 다시 잡는다
    if (tipEl && tipEl === document.activeElement) { const r = tipEl.getBoundingClientRect(); showTip(tipEl, tip.textContent, r.left + r.width / 2, r.top); } else hideTip();
  }, { passive: true });
  const tipAttrs = t => `tabindex="0" role="img" aria-label="${t.replace(/\n/g, ', ')}" data-tip="${t}"`;
  function ktip(el, t) { el.tabIndex = 0; el.setAttribute('role', 'img'); el.setAttribute('aria-label', t.replace(/\n/g, ', ')); el.dataset.ktip = t; }

  /* ---------- 검증 결과 그림: Observable Plot(site/vendor)을 이 메뉴를 처음 열 때 읽는다 ---------- */
  const SRC_OFF = 'tools/measure/README.md 맥 오프라인 평가';
  const charts = [];  // [요소, 그리기 함수]
  function chart(id, draw) { charts.push([$(id), draw]); }
  // 와플: 점 하나 = 사례 하나, 같은 크기라 면적이 곧 개수
  function waffle(el, n, hit, colors, tips) {
    const W = Math.min(el.clientWidth, 640);
    if (!W) return;
    const cols = el.clientWidth < 520 ? 50 : 80, rows = Math.ceil(n / cols), cell = W / cols;
    const data = [{ n: hit, c: colors[0], t: tips[0] }, { n: n - hit, c: colors[1], t: tips[1] }].filter(d => d.n);
    el.replaceChildren(Plot.plot({
      width: W, height: Math.ceil(rows * cell), margin: 0, x: { domain: [0, cols * rows], axis: null }, y: { axis: null },
      marks: [Plot.waffleX(data, { x: 'n', fill: 'c', unit: 1, multiple: rows, gap: Math.max(1, cell * 0.28), rx: '100%', title: 't', tip: true })]
    }));
  }
  const HALO = { stroke: 'var(--bg)', strokeWidth: 4, paintOrder: 'stroke' };  // 기준선 위에 글자가 겹쳐도 읽히게
  // 줄 그림: 왼쪽 이름, 가운데 값 축, 오른쪽 값 글자(마우스 없이도 모든 값이 보이게)
  function rows(el, o) {
    const W = el.clientWidth;
    if (!W) return;
    if (o.kt) ktip(el, o.kt);  // 키보드로 차트 상자에 가면 모든 값과 출처가 풍선으로 뜬다
    // 좁은 화면: 이름과 값을 막대 위 두 줄로 올리고, 막대는 줄 아래쪽에 둔다
    const narrow = W < 600, rowH = narrow ? 74 : (o.rowH || 44), mid = narrow ? 14 : 0;
    const labels = o.rows.map(r => r.label);
    const nRef = (o.refs || []).length;
    const refs = (o.refs || []).flatMap((r, i) => [
      Plot.ruleX([r.v], { stroke: r.soft ? 'var(--text3)' : 'var(--child)', strokeWidth: 1.5, strokeDasharray: r.soft ? '2,3' : null }),
      Plot.text([r.label], narrow
        ? { x: r.v, frameAnchor: 'top', dy: -12 - 15 * (nRef - 1 - i), textAnchor: 'end', dx: 4, fill: r.soft ? 'var(--text3)' : 'var(--child)', fontWeight: 600 }
        : { x: r.v, frameAnchor: 'top', dy: -12, textAnchor: r.anchor || 'middle', dx: r.anchor === 'end' ? -4 : r.anchor === 'start' ? 4 : 0, fill: r.soft ? 'var(--text3)' : 'var(--child)', fontWeight: 600 })
    ]);
    const words = narrow
      ? [Plot.text(o.rows, { y: 'label', text: 'label', frameAnchor: 'left', textAnchor: 'start', lineAnchor: 'bottom', dy: -14, fill: 'var(--text2)', ...HALO }),
         Plot.text(o.rows, { y: 'label', text: d => d.value.replace('\n', ' · '), frameAnchor: 'left', textAnchor: 'start', lineAnchor: 'bottom', dy: 2, fill: 'var(--text)', fontWeight: 650, ...HALO })]
      : [Plot.text(o.rows, { y: 'label', text: 'label', frameAnchor: 'left', textAnchor: 'end', dx: -18, lineWidth: 17, fill: 'var(--text2)' }),
         Plot.text(o.rows, { y: 'label', text: 'value', frameAnchor: 'right', textAnchor: 'start', dx: 18, fill: 'var(--text)', fontWeight: 650 })];
    el.replaceChildren(Plot.plot({
      width: W, height: labels.length * rowH + (nRef ? 30 + (narrow ? 15 * (nRef - 1) : 0) : 8) + 30,
      marginLeft: narrow ? 6 : 230, marginRight: narrow ? 10 : (o.valueW || 140), marginTop: nRef ? 30 + (narrow ? 15 * (nRef - 1) : 0) : 8, marginBottom: 30,
      x: { domain: o.domain, ticks: o.ticks, tickFormat: o.fmt, label: null, tickSize: 4 },
      y: { type: 'band', domain: labels, axis: null, padding: 0 },
      marks: [Plot.frame({ anchor: 'bottom', stroke: 'var(--rule)' }), Plot.ruleY(labels, { stroke: 'var(--line)', dy: mid }), ...refs, ...o.marks({ narrow, dy: mid, ins: t => (rowH - t) / 2 }).filter(Boolean), ...words],
      ariaLabel: o.aria
    }));
  }
  const ms = v => `${num(v)} ms`;
  const sTick = d => d ? `${d / 1000}초` : '0';

  // 울음 입력 → 경보·첫 달래기(blackhole_cry_1113.txt 2차, readme_walkthrough_1113.txt, perf_1108.txt, demo_check_1112.txt 5절, 맥 합성 장면 시험)
  const LAT = [
    { label: '실제 마이크 경로 유효 7회(제출 APK 이전 판)', value: '1.7~2.7초' },
    { label: '주행 시나리오 유효 6회(11-08)', value: '중앙값 2,464 ms' },
    { label: '주행 시나리오 유효 4회(11-12, 3D 화면)', value: '중앙값 약 2,488 ms' },
    { label: '맥 합성 장면 360개(357개 경보)', value: '중앙값 2.4초' }
  ];
  const latPts = [];
  [[0, 'blackhole_cry_1113.txt 2차', [['파일 A(첫 번째 시도에 그친 경우)', 1947], ['파일 B(계속 운 경우)', 1698]]],
   [0, 'readme_walkthrough_1113.txt', [['walkthrough 1회차(11-13)', 2400, '(기록 2.4초, 0.1초 단위)'], ['walkthrough 2회차(11-13)', 2700, '(기록 2.7초, 0.1초 단위)'], ['다시 잼 1회(11-14)', 2000, '(기록 2.0초, 0.1초 단위)'], ['다시 잼 2회(11-14)', 2300, '(기록 2.3초, 0.1초 단위)'], ['다시 잼 3회(11-14)', 2100, '(기록 2.1초, 0.1초 단위)']]],
   [1, 'perf_1108.txt', [['A', 3169], ['B1', 2441], ['B2-1', 2488], ['B2-2', 2426], ['B3', 2440], ['C2', 3515]]],
   [2, 'demo_check_1112.txt 5절', [['시나리오 C', 2585], ['시나리오 B', 2391], ['시나리오 D', 1604], ['시나리오 A', 3166]]]
  ].forEach(([r, src, list]) => {
    const placed = [];
    list.forEach(([k, v, note]) => {
      // 거의 같은 값의 점은 위아래로 비켜 놓아 모두 보이게 한다
      const near = placed.filter(p => Math.abs(p - v) < 50).length;
      placed.push(v);
      latPts.push({ label: LAT[r].label, v, real: r === 0, j: near ? (near % 2 ? -1 : 1) * Math.ceil(near / 2) : 0, t: `${k} ${ms(v)}${note ? ' ' + note : ''}\n${src}` });
    });
  });
  const latMed = [
    { label: LAT[1].label, v: 2464, t: '유효 6회 중앙값 2,464 ms, 최대 3,515 ms\nperf_1108.txt' },
    { label: LAT[2].label, v: 2488, t: '유효 4회 중앙값 약 2,488 ms, 최대 3,166 ms\ndemo_check_1112.txt 5절' },
    { label: LAT[3].label, v: 2400, t: '합성 360장면 중 357장면에서 경보, 첫 경보 중앙값 2.4초\n' + SRC_OFF }
  ];
  const latKT = LAT.map(r => {
    const pts = latPts.filter(p => p.label === r.label).map(p => p.t.split('\n')[0]).join(', ');
    const med = latMed.find(m => m.label === r.label);
    return `${r.label}: ${r.value}` + (pts ? ` (${pts})` : '') + (med ? `, ${med.t.split('\n')[0]}` : '');
  }).join('\n') + '\n' + [...new Set(latPts.concat(latMed).map(p => p.t.split('\n').pop()))].join(', ');
  chart('#p-latency', el => rows(el, {
    kt: latKT, rows: LAT, domain: [0, 4000], ticks: [0, 1000, 2000, 3000, 4000], fmt: sTick, valueW: 150, aria: '울음 시작부터 경보까지 걸린 시간',
    refs: [{ v: 3000, label: '설계 기준: 중앙값 3초 이하', anchor: 'end' }, { v: 3600, label: '설계 기준: 회차마다 3.6초 이하', anchor: 'start', soft: true }],
    marks: g => [
      Plot.tickX(latMed, { x: 'v', y: 'label', stroke: 'var(--text)', strokeWidth: 2, insetTop: g.ins(20) + g.dy, insetBottom: g.ins(20) - g.dy }),
      ...[...new Set(latPts.map(p => p.j))].map(j => Plot.dot(latPts.filter(p => p.j === j), { x: 'v', y: 'label', dy: j * 7 + g.dy, r: 5, fill: d => d.real ? 'var(--accent)' : 'var(--text3)', stroke: 'var(--bg)', strokeWidth: 2 })),
      g.narrow ? null : Plot.text([{ label: LAT[1].label, v: 3515 }, { label: LAT[2].label, v: 3166 }], { x: 'v', y: 'label', text: d => `최대 ${num(d.v)}`, dy: -14, fontSize: 11, fill: 'var(--text3)' }),
      // 가까운 점의 세로선과 축 위 값(이름 칸과 겹치지 않게 x만 보인다)
      Plot.ruleX(latPts, Plot.pointer({ px: 'v', py: 'label', x: 'v', stroke: 'var(--text3)', strokeDasharray: '2,3' })),
      Plot.text(latPts, Plot.pointer({ px: 'v', py: 'label', x: 'v', text: d => num(d.v), frameAnchor: 'bottom', dy: 13, fill: 'var(--text)', fontWeight: 650, ...HALO })),
      Plot.tip(latPts.concat(latMed), Plot.pointer({ x: 'v', y: 'label', title: 't' }))
    ]
  }));

  // 자원 사용량(demo_check_1112.txt 7절): [구간, 표본, 평균, 중앙, 95%, 최대, PSS 최소, PSS 최대]
  const PSRC = 'demo_check_1112.txt 7절';
  const PERF = [
    ['정차 홈 대기 60초(마이크 꺼짐)', 26, 1.63, 0.0, 4.0, 7.6, 219, 227],
    ['시나리오 B 전체(더운 실내)', 202, 3.13, 3.8, 8.0, 34.6, 232, 249],
    ['시나리오 B 울음 뒤', 73, 4.03, 4.0, 8.0, 34.6, 238, 249],
    ['시나리오 D 전체(두 번 실패 뒤 정차 안내)', 261, 2.74, 3.8, 8.0, 73.0, 239, 251],
    ['시나리오 D 울음 4분', 106, 2.96, 3.8, 8.0, 20.0, 239, 251],
    ['시나리오 A 전체(첫 번째 시도에 그친 시나리오)', 530, 2.53, 0.0, 8.0, 28.2, 234, 252],
    ['시나리오 A 울음 뒤', 60, 2.79, 3.8, 8.0, 11.5, 247, 252]
  ].map(([label, n, avg, med, p95, max, lo, hi]) => ({ label, n, avg, med, p95, max, lo, hi }));
  chart('#p-cpu', el => rows(el, {
    kt: PERF.map(d => `${d.label}(표본 ${d.n}): 평균 ${f2(d.avg)}% · 중앙 ${f1(d.med)}% · 95% ${f1(d.p95)}% · 최대 ${f1(d.max)}%`).join('\n') + `\n${PSRC}`,
    rows: PERF.map(d => ({ ...d, value: `평균 ${f2(d.avg)}%\n95% ${f1(d.p95)}% · 최대 ${f1(d.max)}%` })),
    domain: [0, 10], ticks: [0, 2, 4, 6, 8, 10], fmt: d => d + '%', valueW: 170, aria: '구간별 앱 CPU 평균, 95% 값, 순간 최대',
    refs: [{ v: 5, label: '주행 중 기준 5% 미만' }],
    marks: g => [
      Plot.barX(PERF, { x: 'avg', y: 'label', fill: 'var(--accent)', insetTop: g.ins(8) + g.dy, insetBottom: g.ins(8) - g.dy, rx: 4 }),
      Plot.tickX(PERF, { x: 'p95', y: 'label', stroke: 'var(--text)', strokeWidth: 2, insetTop: g.ins(20) + g.dy, insetBottom: g.ins(20) - g.dy }),
      Plot.dot(PERF.filter(d => d.max <= 10), { x: 'max', y: 'label', dy: g.dy, r: 4, stroke: 'var(--text3)', strokeWidth: 1.5 }),
      Plot.dot(PERF.filter(d => d.max > 10), { x: () => 10, y: 'label', dy: g.dy, symbol: 'triangle', rotate: 90, r: 4, fill: 'var(--text3)' }),
      Plot.tip(PERF, Plot.pointerY({ x: 'avg', y: 'label', title: d => `${d.label} · 표본 ${d.n}\n평균 ${f2(d.avg)}% · 중앙 ${f1(d.med)}% · 95% ${f1(d.p95)}% · 최대 ${f1(d.max)}%\n${PSRC}` }))
    ]
  }));
  chart('#p-mem', el => rows(el, {
    kt: PERF.map(d => `${d.label}: PSS ${d.lo}~${d.hi} MB`).join('\n') + `\n${PSRC}`,
    rows: PERF.map(d => ({ ...d, value: `${d.lo}~${d.hi} MB` })),
    domain: [0, 420], ticks: [0, 100, 200, 300, 400], fmt: d => d === 400 ? '400MB' : d, aria: '구간별 앱 메모리(PSS) 범위',
    refs: [{ v: 400, label: '기준 400MB 이하' }],
    marks: g => [
      Plot.barX(PERF, { x1: 'lo', x2: 'hi', y: 'label', fill: 'var(--accent)', insetTop: g.ins(8) + g.dy, insetBottom: g.ins(8) - g.dy, rx: 4 }),
      Plot.tip(PERF, Plot.pointerY({ x: 'hi', y: 'label', title: d => `${d.label}: PSS ${d.lo}~${d.hi} MB\n${PSRC}` }))
    ]
  }));

  // 소리 고르기 시뮬레이션(tools/sim/bandit_curve_1115.txt에서 옮긴 값): 울음 n번째(1~20)에 그 아이의 최선 방법을 고른 아이의 비율(%)과 마지막 5번 평균
  const CURVE = {
    big: {
      fixed: [33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8, 33.8],
      random: [33.6, 32.7, 33.6, 33.9, 33.4, 33.2, 33.1, 33.4, 33.9, 33.9, 32.7, 33.6, 33.4, 32.8, 33.7, 33.1, 33.6, 33.1, 33.4, 32.8],
      rotate: [33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 33.8, 33.8],
      thompson: [33.8, 33.8, 32.4, 33.8, 33.8, 32.4, 49.7, 51.3, 53.1, 54.0, 55.3, 56.2, 57.0, 58.4, 58.8, 59.9, 60.2, 61.3, 61.5, 62.6],
      avg: { fixed: 33.8, random: 33.2, rotate: 33.5, thompson: 61.1 }
    },
    mid: {
      fixed: [34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4, 34.4],
      random: [33.4, 33.2, 33.4, 33.0, 33.5, 33.1, 33.6, 32.5, 33.7, 33.7, 33.5, 32.9, 32.9, 33.2, 33.6, 32.5, 33.2, 33.4, 33.5, 33.2],
      rotate: [34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 34.4, 32.5],
      thompson: [34.4, 32.5, 33.1, 34.4, 32.5, 33.1, 41.1, 42.6, 43.4, 43.3, 43.5, 44.3, 45.1, 45.7, 45.9, 46.6, 47.6, 47.5, 47.7, 48.4],
      avg: { fixed: 34.4, random: 33.2, rotate: 33.4, thompson: 47.6 }
    }
  };
  const SIM = 'tools/sim/bandit_curve_1115.txt';
  const CURVE_NAME = { thompson: '톰슨 샘플링(아이곁)', fixed: '늘 같은 방법', random: '무작위', rotate: '돌아가며' };
  const simCurve = S => el => {
    const W = el.clientWidth;
    if (!W) return;
    const narrow = W < 600, keys = Object.keys(CURVE_NAME), gray = keys.filter(k => k !== 'thompson');
    const wide = S.thompson.map((_, i) => ({ n: i + 1, ...Object.fromEntries(keys.map(k => [k, S[k][i]])) }));
    const long = k => wide.map(d => ({ n: d.n, v: d[k], k }));
    // 끝 이름표: 회색 세 선은 값이 거의 같아 겹치므로 평균 순서대로 위아래로 벌려 놓는다
    const H = narrow ? 230 : 250, step = 15 / ((H - 46) / 70);  // 이름표 사이 15px을 값 단위로 바꾼 간격
    const mid = gray.reduce((a, k) => a + S.avg[k], 0) / gray.length;
    const tags = [{ k: 'thompson', y: S.thompson[19] }, ...gray.slice().sort((a, b) => S.avg[b] - S.avg[a]).map((k, i) => ({ k, y: mid - (i - 1) * step }))]
      .map(d => ({ ...d, t: `${CURVE_NAME[d.k]} ${f1(S.avg[d.k])}%` }));
    el.replaceChildren(Plot.plot({
      width: W, height: H, marginLeft: 40, marginRight: narrow ? 142 : 176, marginTop: 8, marginBottom: 38,
      x: { domain: [0.5, 20.5], ticks: [1, 5, 10, 15, 20], label: '울음 n번째', tickSize: 4 },
      y: { domain: [0, 70], ticks: [0, 20, 40, 60], tickFormat: d => d + '%', label: null, tickSize: 0 },
      marks: [
        Plot.rectX([{ a: 0.5, b: 6.5 }], { x1: 'a', x2: 'b', fill: 'var(--track)' }),
        Plot.text([{ x: 3.5 }], { x: 'x', text: () => narrow ? '톰슨:\n처음 6번은\n돌아가며' : '톰슨: 처음 6번은 돌아가며', frameAnchor: 'top', dy: 8, fontSize: 12, fill: 'var(--text3)' }),
        Plot.gridY({ stroke: 'var(--line)' }),
        Plot.frame({ anchor: 'bottom', stroke: 'var(--rule)' }),
        Plot.line(gray.flatMap(long), { x: 'n', y: 'v', z: 'k', stroke: 'var(--text3)', strokeWidth: 1.25 }),
        Plot.line(long('thompson'), { x: 'n', y: 'v', stroke: 'var(--accent)', strokeWidth: 2.75 }),
        Plot.text(tags, { x: () => 20, y: 'y', text: 't', dx: 8, textAnchor: 'start', fontSize: 12, fill: d => d.k === 'thompson' ? 'var(--accent)' : 'var(--text2)', fontWeight: d => d.k === 'thompson' ? 650 : 500 }),
        Plot.ruleX(wide, Plot.pointerX({ x: 'n', maxRadius: 200, stroke: 'var(--text3)', strokeDasharray: '2,3' })),
        Plot.tip(wide, Plot.pointerX({ x: 'n', y: 'thompson', maxRadius: 200, title: d => `울음 ${d.n}번째 · 최선 방법을 고른 비율\n` + keys.map(k => `${CURVE_NAME[k]} ${f1(d[k])}%`).join('\n') + `\n${SIM}` }))
      ],
      ariaLabel: '울음 회차별로 가장 맞는 방법을 고른 아이의 비율'
    }));
    ktip(el, tags.map(d => d.t).join(', ') + ` (마지막 5번 평균)\n${SIM}`);
  };
  chart('#p-sim1', simCurve(CURVE.big));
  chart('#p-sim2', simCurve(CURVE.mid));

  // 음성 질문(llm_p7_1111.txt 1절)
  const LLM = [{ label: '중앙값', v: 1864, c: 'var(--accent)' }, { label: '90% 값', v: 2456, c: 'var(--text3)' }].map(d => ({ ...d, value: ms(d.v) }));
  chart('#p-llm', el => rows(el, {
    kt: LLM.map(d => `365번의 ${d.label} ${ms(d.v)}`).join(', ') + '\nllm_p7_1111.txt 1절',
    rows: LLM, domain: [0, 3500], ticks: [0, 1000, 2000, 3000], fmt: sTick, rowH: 40, aria: '음성 질문 LLM 답변 지연',
    refs: [{ v: 3000, label: '차가 기다리는 한도 3초' }],
    marks: g => [
      Plot.barX(LLM, { x: 'v', y: 'label', fill: 'c', insetTop: g.ins(8) + g.dy, insetBottom: g.ins(8) - g.dy, rx: 4 }),
      Plot.tip(LLM, Plot.pointerY({ x: 'v', y: 'label', title: d => `365번의 ${d.label} ${ms(d.v)}\nllm_p7_1111.txt 1절` }))
    ]
  }));

  // 와플(울음 감지, 오경보, 음성 질문)
  const FALSE = [['말소리', 0, 497], ['음악', 0, 144], ['동요(CSD)', 7, 107], ['그 밖의 소리', 15, 1960]];
  $('#m-false').innerHTML = FALSE.map(([k, a, n], i) => {
    const share = a ? ` (${f1(a / n * 100)}%)` : '';
    return `<div class="mrow"><span class="mk">${k}<b>${a}/${num(n)}${share}</b></span><div class="mm" id="m-false-${i}"></div></div>`;
  }).join('');
  ktip($('#m-recall'), `울음 녹음 457개 중 378개 경보(0.83), 79개 놓침\n${SRC_OFF}`);
  chart('#m-recall', el => waffle(el, 457, 378, ['var(--accent)', 'var(--mute)'], [`경보 378/457(0.83)\n${SRC_OFF}`, `놓침 79/457\n${SRC_OFF}`]));
  FALSE.forEach(([k0, a, n], i) => {
    const k = k0 === '그 밖의 소리' ? '그 밖의 소리(ESC-50 자료)' : k0;  // 자료 이름은 풍선에만 쓴다
    const share = a ? ` (${f1(a / n * 100)}%)` : '';
    ktip($('#m-false-' + i), `${k}: ${num(n)}개 중 ${a}개에 경보${share}${i ? '' : '\n말소리 중 판정 구간이 하나뿐인 짧은 클립은 \'3초 구간 두 번 연속\' 규칙상 경보를 낼 수 없습니다(그 수는 기록에 없음)'}\n${SRC_OFF}`);
    chart('#m-false-' + i, el => waffle(el, n, a, ['var(--child)', 'var(--mute)'], [`${k} 오경보 ${a}/${num(n)}${share}\n${SRC_OFF}`, `${k} 경보 없음 ${num(n - a)}/${num(n)}\n${SRC_OFF}`]));
  });
  ktip($('#m-llm'), '363번 중 9번이 3초를 넘김(2.5%)\nllm_p7_1111.txt 1절');
  chart('#m-llm', el => waffle(el, 363, 9, ['var(--child)', 'var(--mute)'], ['3초 넘김 9/363(2.5%)\nllm_p7_1111.txt 1절', '3초 안 354/363\nllm_p7_1111.txt 1절']));

  let plotOK = false;
  const widths = new Map();
  function drawCharts() {
    let drew = false;
    charts.forEach(([el, draw]) => {
      const w = el.clientWidth;
      if (!w || widths.get(el) === w) return;
      widths.set(el, w);
      draw(el);
      drew = true;
    });
    // 바로가기로 연 절은 그림이 처음 다 그려진 직후 한 번만 다시 맞추고 앵커를 푼다(그 뒤 창 크기를 바꿔도 끌려가지 않는다)
    if (drew) { settleAnchor(false); clearTimeout(settleT); settleT = setTimeout(() => settleAnchor(true), 400); }
  }
  onShow.push(id => {
    if (id !== 'results' || plotOK) return;
    loadOnce(['vendor/d3/d3.min.js', 'vendor/plot/plot.umd.min.js']).then(() => {
      plotOK = true;
      drawCharts();
      // 너비가 바뀔 때만 다시 그린다(다음 프레임에 그려 ResizeObserver 되먹임을 막는다)
      const ro = new ResizeObserver(() => requestAnimationFrame(drawCharts));
      charts.forEach(([el]) => ro.observe(el));
    }, () => { charts.forEach(([el]) => { el.textContent = '그림을 불러오지 못했습니다. 값은 왼쪽 글과 원본 기록에 있습니다.'; }); });
  });

  /* ---------- 그침 판정 칸: tools/cry-retrain/rt_outcome.json의 [그침 맞힘, 계속 맞힘](각 18장면) ---------- */
  const ARMS = [['none', '재생 없음'], ['wn10', '백색소음'], ['brown10', '브라운 잡음'], ['song10', '동요 10'], ['song20', '동요 20']];
  const RT = {
    R3: { c10: { none: [18, 18], wn10: [18, 17], brown10: [18, 18], song10: [18, 18], song20: [18, 18] },
          c0: { none: [18, 18], wn10: [18, 18], brown10: [18, 18], song10: [18, 18], song20: [18, 18] } },
    R1: { c10: { none: [18, 18], wn10: [18, 17], brown10: [18, 18], song10: [14, 18], song20: [14, 18] },
          c0: { none: [18, 18], wn10: [18, 18], brown10: [18, 18], song10: [12, 18], song20: [14, 18] } }
  };
  const RT_NAME = { R3: 'R3_듣기틈+확인', R1: 'R1_연속' };
  let cgMode = 'R3', cgW = 0;
  function renderCG() {
    const host = $('#cg'), w = host.clientWidth;
    if (!w) return;
    cgW = w;
    const data = RT[cgMode];
    let total = 0, song = 0;
    const P = Math.max(5, Math.min(8, Math.floor((Math.min(w, 420) - 98) / 36)));
    host.innerHTML = [['c10', 10], ['c0', 0]].map(([key, db]) => {
      let cells = '', hits = '', box = '', sum = 0;
      const wrong = [];
      ARMS.forEach(([arm, label], row) => {
        data[key][arm].forEach((ok, g) => {
          const x0 = g * (18 * P + 10), y = row * 22 + 4;
          for (let i = 0; i < 18; i++) cells += `<rect x="${x0 + i * P}" y="${y}" width="${P - 2}" height="14" rx="1.5" class="${i < ok ? 'c-ok' : 'c-bad'}"/>`;
          const what = g ? '계속 운' : '그친';
          hits += `<rect x="${x0}" y="${row * 22}" width="${18 * P}" height="22" class="c-hit" data-tip="${label} · 소음 비 ${db}dB · ${what} 장면 ${ok}/18\nrt_outcome.json ${RT_NAME[cgMode]}"/>`;
          if (ok < 18) wrong.push(`${label}·${what} ${18 - ok}`);
          if (!g && arm.startsWith('song')) song += ok;
          sum += ok;
        });
      });
      // 동요 장면에서 실제로 그친 칸(비교의 모집단)을 테두리로 묶는다
      box = `<rect x="-2.5" y="${3 * 22 + 1.5}" width="${18 * P + 3}" height="${2 * 22 - 1}" rx="3" class="c-box"/>`;
      total += sum;
      const W = 36 * P + 8, summary = `울음 대 차 소음 비 ${db}dB: ${sum}/180 맞힘` + (wrong.length ? `, 틀림 ${wrong.join(', ')}` : '');
      return `<div class="cblock" ${tipAttrs(summary)}><h4>소음 비 ${db}dB</h4><div class="chead" style="width:${W}px"><span>그침 18</span><span>계속 18</span></div>` +
        `<div class="clabels">${ARMS.map(a => `<span>${a[1]}</span>`).join('')}</div>` +
        `<svg viewBox="-4 0 ${W + 4} 110" width="${W + 4}" height="110" aria-hidden="true">${cells}${box}${hits}</svg></div>`;
    }).join('');
    $('#cg-v').innerHTML = `${total}<i>/360</i>`;
    $('#cg-song').innerHTML = `${song}<i>/72</i>`;
    $('#cg-n').textContent = cgMode === 'R3' ? '56초 틀고 멈춘 뒤 8초 듣고, 조용하면 소리를 끈 채 30초 더 확인할 때' : '재생을 끄지 않고 t0+30~60초로 판정할 때(비교용)';
  }
  radios($('#cg-mode'), b => { cgMode = b.dataset.mode; renderCG(); });
  new ResizeObserver(() => requestAnimationFrame(() => { if ($('#cg').clientWidth !== cgW) renderCG(); })).observe($('#cg'));

  /* ---------- 크게 보기 ---------- */
  const dlg = $('#viewer'), vImg = $('#v-img'), vFrame = $('#v-frame');
  let vList = [], vAt = 0;
  function vDraw() {
    const it = vList[vAt];
    vFrame.className = 'v-frame ' + it.kind;
    vImg.src = it.src; vImg.alt = it.alt;
    $('#v-cap').textContent = it.cap;
    $('#v-count').textContent = vList.length > 1 ? `${vAt + 1} / ${vList.length}` : '';
    $('#v-prev').disabled = $('#v-next').disabled = vList.length < 2;
  }
  function vOpen(list, i) { vList = list; vAt = i; vDraw(); dlg.showModal(); }
  function vStep(d) { if (vList.length < 2) return; vAt = (vAt + d + vList.length) % vList.length; vDraw(); }
  $('#v-prev').addEventListener('click', () => vStep(-1));
  $('#v-next').addEventListener('click', () => vStep(1));
  $('#v-close').addEventListener('click', () => dlg.close());
  dlg.addEventListener('keydown', e => { if (e.key === 'ArrowRight') vStep(1); else if (e.key === 'ArrowLeft') vStep(-1); });
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });
  const gallery = $$('[data-page="screens"] .shot').map((b, i) => {
    const img = $('img', b), li = b.parentNode, p = $('p', li), n = $('b', p);
    const head = $('h2', b.closest('.shots-group')).textContent;
    const text = p.textContent.slice(n ? n.textContent.length : 0);
    b.addEventListener('click', () => vOpen(gallery, i));
    b.setAttribute('aria-label', `${img.alt}, 크게 보기`);
    return { src: img.getAttribute('src'), alt: img.alt, cap: `${head} · ${text}`, kind: b.closest('.phone') ? 'phone' : 'car' };
  });

  // 차 화면에서 바뀌는 곳: 누르면 그 묶음의 전체 화면을 확대 보기로 연다
  $$('.chg').forEach(g => {
    const cards = $$('.chg-card', g);
    const list = cards.map(c => ({ src: `img/${c.dataset.full}.jpg`, alt: `차 화면: ${c.dataset.say}`, cap: `${c.dataset.name} · ${c.dataset.say}`, kind: 'car' }));
    cards.forEach((c, i) => c.addEventListener('click', () => vOpen(list, i)));
  });

  /* ---------- 3D: 묶음(약 5.4MB)은 첫 화면을 그린 뒤 무대가 보일 때만, 한 번만 읽는다 ---------- */
  const BUNDLE = ['design/vendor/three/three-bundle.min.js', 'design/js/gen-car.js', 'design/js/core.js', 'design/js/car3d.js'];
  function webgl() {
    try { const c = document.createElement('canvas'); return !!(window.WebGLRenderingContext && (c.getContext('webgl2') || c.getContext('webgl'))); } catch (e) { return false; }
  }
  function mount3D(stage, preset, ready) {
    const note = $('.stage-note', stage), gl = $('.gl', stage);
    let view = null;
    $('.stage-reset', stage).addEventListener('click', () => { if (view) view.fly(view.o.preset, false); });
    afterLoad(() => {
      const io = new IntersectionObserver(es => {
        if (!es.some(e => e.isIntersecting)) return;
        io.disconnect();
        if (!webgl()) { note.textContent = '이 브라우저에서는 WebGL을 쓸 수 없어 정지 그림을 보입니다'; return; }
        note.textContent = '실내 3D를 불러오는 중…';
        loadOnce(BUNDLE).then(() => {
          // 기능 설명용 평면도 시점(위에서 내려다보고 차 앞이 왼쪽). design/의 파일은 고치지 않고 실행 중에만 더한다
          Car3D.PRESET.plan = Car3D.PRESET.plan || { az: 90, el: 88, d: 6.9, R: 1.6, fov: 30, t: [-0.3, 0.5, 0], xray: 1 };
          return new Car3D.View(gl, { preset }).ready;
        }).then(v => {
          view = v;
          v.controls.enableZoom = false;  // 휠은 페이지 스크롤에 남겨 둔다
          stage.classList.add('live');
          note.textContent = '끌어서 돌려 볼 수 있습니다';
          ready(v);
        }).catch(() => { note.textContent = '실내 3D를 불러오지 못해 정지 그림을 보입니다'; });
      }, { rootMargin: '200px' });
      io.observe(stage);
    });
  }

  /* ---------- 실제 주행 기록 다시 보기 ----------
     tools/measure/blackhole_cry_1113.txt '2차' 절의 에뮬레이터 로그 시각.
     울음 시작(t0)은 경보 시각 - 기록의 '울음 시작 → 경보' 값이다(A 15:00:54.375, B 15:02:55.630). */
  const sec = t => { const [h, m, s] = t.split(':'); return +h * 3600 + +m * 60 + parseFloat(s); };
  const S3D = {
    listen: { light: 'listen', listen: true, ripple: false, callouts: [], say: '실내: 뒷좌석 소리를 듣는 중' },
    alert: { light: 'alert', listen: false, ripple: false, callouts: [], say: '실내: 울음 경보' },
    soothe: { light: 'soothe', listen: false, ripple: true, callouts: [], say: '실내: 뒷좌석 출력으로 재생' },
    base: { light: 'base', listen: false, ripple: false, callouts: [], say: '실내: 달래기 끝, 다시 듣기' },
    handoff: { light: 'alert', listen: false, ripple: false, callouts: [], say: '실내: 자동 달래기 멈춤' }
  };
  const RUNS = {
    A: {
      t0: '15:00:54.375',
      after: '이후 파일 B',
      rec: [[0, 47.41, 'cry', '울음 48.3초', '파일 A의 울음 부분(파일 0~48.3초). 첫 소리(파일 0.89초)를 0으로 둠'], [47.41, 87.41, 'quiet', '조용함 40초', '파일 A의 조용한 부분 40초']],
      phases: [
        ['15:00:18.398', '15:00:56.322', 'listen', '듣기'],
        ['15:00:56.322', '15:01:52.325', 'play', '백색소음 56.0초'],
        ['15:01:52.325', '15:02:00.776', 'check', '멈추고 듣기'],
        ['15:02:00.776', '15:02:31.002', 'check', '소리 끈 채 확인'],
        ['15:02:31.002', '15:02:57.328', 'listen', '다시 듣기']  // 같은 주행에서 이 시각에 파일 B의 경보로 이어진다(blackhole_cry_1113.txt 4절)
      ],
      steps: [
        { t: '15:00:18.398', n: '주행 중 듣기', img: 'r1_a1', cap: '아기 소리를 듣고 있어요', s: 'listen', log: ['LISTENING'],
          body: '기어 D·시속 30으로 바꾸자 운행 세션이 듣기 단계로 들어갔습니다.' },
        { t: '15:00:54.38', approx: true, n: '울음 시작', img: 'r1_a1', cap: '아기 소리를 듣고 있어요', s: 'listen', log: [],
          body: '맥에서 파일 A(울음 48.3초 + 조용함 40초)를 틀었습니다. 시각은 첫 소리를 에뮬레이터 시각으로 옮긴 값입니다.' },
        { t: '15:00:56.322', d: '+1,947 ms', n: '경보', img: 'r1_a2', cap: '뒷자리에서 소리를 틀고 있어요', s: 'alert', log: ['ATTEMPT', 'USAGE_MEDIA'],
          body: '울음으로 판정해 달래기 단계로 넘어가고, 4ms 뒤 재생 오디오 포커스를 요청했습니다.' },
        { t: '15:00:56.348', d: '+1,973 ms', n: '뒷좌석 재생', img: 'r1_a2', cap: '뒷자리에서 소리를 틀고 있어요', s: 'soothe', log: ['bus100_audio_zone_1'],
          body: '뒷좌석 출력을 열고 백색소음 재생을 시작했습니다. 음성 안내는 +2.0초에 시작했습니다.', voice: '뒤에서 울어요. 뒷자리에 백색소음 틀었어요.' },
        { t: '15:01:52.325', n: '멈추고 듣기', img: 'r1_a3', cap: '그쳤는지 보고 있어요', s: 'listen', log: ['GAP'],
          body: '56.0초 뒤 재생을 멈추고 들었습니다. 녹음의 울음은 재생 중에 끝났습니다.' },
        { t: '15:02:00.776', n: '소리 끈 채 확인', img: 'r1_a3', cap: '그쳤는지 보고 있어요', s: 'listen', log: ['CONFIRM'],
          body: '소리를 끈 채 더 확인했습니다. 확인 중 새 말하기·재생은 없었습니다.' },
        { t: '15:02:31.002', n: '그침 판정', img: 'r1_a4', cap: '아기 소리를 듣고 있어요', s: 'base', log: ['LISTENING'],
          body: '그친 것으로 판정하고 다시 듣습니다. 차 리포트: 자동 1차 백색소음 56초, 결과 그침.' }
      ]
    },
    B: {
      t0: '15:02:55.630',
      rec: [[0, 192.41, 'cry', '파일 B 193.3초(같은 울음을 네 번 이음)', '파일 B 193.3초, 같은 울음을 네 번 이은 녹음. 첫 소리(파일 0.89초)를 0으로 둠']],
      phases: [
        ['15:02:31.002', '15:02:57.328', 'listen', '듣기'],
        ['15:02:57.328', '15:03:53.328', 'play', '뱃속 소리 56.0초'],
        ['15:03:53.328', '15:03:56.802', 'check', '멈추고 듣기'],
        ['15:03:56.802', '15:04:52.802', 'play', '백색소음 56.0초'],
        ['15:04:52.802', '15:04:56.277', 'check', '멈추고 듣기'],
        ['15:04:56.277', null, 'handoff', '자동 달래기 멈춤']
      ],
      steps: [
        { t: '15:02:31.002', n: '주행 중 듣기', img: 'r1_b1', cap: '아기 소리를 듣고 있어요', s: 'listen', log: ['LISTENING'],
          body: '같은 주행에서 파일 A의 에피소드가 끝난 뒤 다시 듣는 중입니다.' },
        { t: '15:02:55.63', approx: true, n: '울음 시작', img: 'r1_b1', cap: '아기 소리를 듣고 있어요', s: 'listen', log: [],
          body: '맥에서 파일 B(같은 울음을 네 번 이은 193.3초)를 틀었습니다.' },
        { t: '15:02:57.328', d: '+1,698 ms', n: '경보', img: 'r1_b2', cap: '뒷자리에서 소리를 틀고 있어요', s: 'alert', log: ['ATTEMPT'],
          body: '울음으로 판정해 달래기 단계로 넘어가고 재생 오디오 포커스를 요청했습니다.' },
        { t: '15:02:57.497', d: '+1,867 ms', n: '뒷좌석 재생', img: 'r1_b2', cap: '뒷자리에서 소리를 틀고 있어요', s: 'soothe', log: ['bus100_audio_zone_1'],
          body: '첫 번째 시도로 뱃속 소리 재생을 시작했습니다(뒷좌석 출력 열림). 음성 안내는 +1.8초에 시작했습니다.', voice: '뒤에서 울어요. 뒷자리에 뱃속 소리 틀었어요.' },
        { t: '15:03:53.328', n: '멈추고 듣기', img: 'r1_b3', cap: '그쳤는지 보고 있어요', s: 'listen', log: ['GAP'],
          body: '56초 뒤 재생을 멈추고 들었고, 아직 울고 있었습니다.' },
        { t: '15:03:56.802', n: '두 번째 시도', img: 'r1_b4', cap: '뒷자리에서 소리를 틀고 있어요', s: 'soothe', log: ['ATTEMPT'],
          body: '소리를 백색소음으로 바꿔 다시 재생을 시작했습니다.', voice: '아직 울어요. 백색소음으로 바꿨어요.' },
        { t: '15:04:52.802', n: '멈추고 듣기', img: 'r1_b5', cap: '그쳤는지 보고 있어요', s: 'listen', log: ['GAP'],
          body: '백색소음 재생도 56.0초 뒤 멈추고 다시 들었습니다.' },
        { t: '15:04:56.277', n: '정차 안내', img: 'r1_b6', cap: '자동 달래기를 멈췄어요', s: 'handoff', log: ['HANDOFF'],
          body: '다시 멈추고 들었지만 계속 울어 자동 달래기를 멈췄습니다. 이 회차는 길안내를 켜지 않은 시험이었습니다. 차 리포트: 결과 계속 울음.',
          voice: '경유지로 넣을 수 있는 가까운 쉼터를 못 찾았어요. 안전한 곳에 세워서 돌봐 주세요.' }
      ]
    }
  };
  const DOM0 = -8, DOM1 = 200;  // 두 기록을 같은 비율로 견주려고 자를 같게 둔다(초, 울음 시작 = 0)
  const X = s => Math.max(DOM0, Math.min(DOM1, s));
  const L = s => ((X(s) - DOM0) / (DOM1 - DOM0) * 100).toFixed(3) + '%';
  const W = (a, b) => ((X(b) - X(a)) / (DOM1 - DOM0) * 100).toFixed(3) + '%';
  const dur = (a, b) => (sec(b) - sec(a)).toFixed(1) + '초';
  // 시각 t에서 마지막으로 지난 단계(구간 누르기와 슬라이더가 같은 함수를 쓴다)
  function stepAt(steps, t) {
    let i = 0;
    steps.forEach((s, k) => { if (X(s.x) <= t + 0.01) i = k; });
    return i;
  }
  Object.keys(RUNS).forEach(k => {
    const R = RUNS[k], t0 = sec(R.t0);
    R.steps.forEach(s => { s.x = sec(s.t) - t0; });
    R.phases.forEach(p => { p.a = sec(p[0]) - t0; p.b = p[1] ? sec(p[1]) - t0 : Infinity; });
    R.phases.forEach(p => { p.step = stepAt(R.steps, X(p.a) + 0.25); });  // 경보 직후 재생이 시작되기까지 0.2초 안팎이라 그만큼 여유를 둔다
  });

  const stepsEl = $('#rp-steps'), rulerEl = $('#rp-ruler'), playBtn = $('#rp-play');
  let runK = 'A', at = 0, curT = DOM0, timer = 0, rpView = null;
  const run = () => RUNS[runK];
  const fmtT = t => t <= DOM0 ? '전' : (t < 0 ? '−' : '+') + Math.abs(t).toFixed(1) + '초';
  const phaseAt = (k, t) => {
    const R = RUNS[k], p = R.phases.find(p => t >= p.a && t < p.b);
    return p ? p[3] : (t >= R.phases[R.phases.length - 1].b ? `기록 끝(${R.after})` : '듣기');
  };

  // 녹음 줄의 파형: 0.25초 칸의 소리 크기(wave.js)를 시간 자 위에 세로 막대로 그린다. 울음 구간은 울음 색, 나머지는 옅은 색
  function waveSVG(k) {
    const cw = window.CRY_WAVE;
    if (!cw || !cw[k]) return '';
    const [ca, cb] = RUNS[k].rec[0].slice(0, 2), span = DOM1 - DOM0, cry = [], quiet = [];
    cw[k].forEach((v, i) => {
      const t = i * cw.dt - cw.t0;
      if (!v || t + cw.dt <= DOM0 || t >= DOM1) return;
      const h = v / 100 * 20, x = ((Math.max(t, DOM0) - DOM0) / span * 1000).toFixed(2), w = (cw.dt / span * 1000).toFixed(2);
      (t + cw.dt / 2 >= ca && t + cw.dt / 2 <= cb ? cry : quiet).push(`M${x} ${((20 - h) / 2).toFixed(1)}h${w}v${h.toFixed(1)}h-${w}z`);
    });
    return `<svg class="wave" viewBox="0 0 1000 20" preserveAspectRatio="none" aria-hidden="true"><path class="wv-q" d="${quiet.join('')}"/><path class="wv-c" d="${cry.join('')}"/></svg>`;
  }
  // 두 기록을 같은 시간 자에 나란히 그린다. 고른 기록은 진하게, 다른 기록은 흐리게
  function renderRuler() {
    let html = '';
    ['A', 'B'].forEach((k, ri) => {
      const R = RUNS[k];
      const rec = R.rec.map(([a, b, cls, label, t]) => `<i class="band b-${cls}" style="left:${L(a)};width:${W(a, b)}" data-tip="${t}"><span>${label}</span></i>`).join('');
      const ph = R.phases.map(p => {
        const t = `파일 ${k} · ${p[3]}\n${p[0]} → ${p[1] ? `${p[1]} (${dur(p[0], p[1])})` : '기록 끝까지'}`;
        return `<i class="band b-${p[2]}" style="left:${L(p.a)};width:${W(p.a, Math.min(p.b, DOM1))}" data-run="${k}" data-step="${p.step}" data-tip="${t}"><span>${p[3]}</span></i>`;
      }).join('') + (R.after && isFinite(R.phases[R.phases.length - 1].b)
        ? `<i class="band b-end" style="left:${L(R.phases[R.phases.length - 1].b)};right:0" data-tip="파일 ${k}의 기록은 여기까지입니다. 같은 주행에서 이 시각에 파일 B의 경보가 이어집니다."><span>이후는 파일 B</span></i>` : '');
      html += `<span class="lane-k r${ri * 2 + 1}" data-run="${k}">${k} · 녹음</span><div class="lane rec r${ri * 2 + 1}" data-run="${k}">${rec}${waveSVG(k)}</div>` +
        `<span class="lane-k r${ri * 2 + 2}" data-run="${k}">${k} · 앱 단계</span><div class="lane r${ri * 2 + 2}" data-run="${k}">${ph}</div>`;
    });
    const ticks = [];
    for (let s = 0; s <= DOM1; s += 20) ticks.push(`<span class="${s % 40 ? 'minor' : ''}" style="left:${L(s)}">${s === 0 ? '울음 시작' : s + '초'}</span>`);
    rulerEl.innerHTML = html + `<div class="cursor-wrap"><i class="cursor" id="rp-cursor"></i></div><div class="axis">${ticks.join('')}</div>` +
      `<input type="range" class="slider" id="rp-slider" min="${DOM0}" max="${DOM1}" step="0.5" value="${DOM0}" aria-label="울음 시작부터의 시각(초), 두 기록 비교">` +
      `<p class="rp-at" id="rp-at" aria-hidden="true"></p>`;
    $('#rp-slider').addEventListener('input', e => { stop(); setT(+e.target.value); });
  }
  function fitBands() {
    // 띠가 글자보다 좁으면 글자를 숨긴다(값은 풍선에 남는다)
    $$('.band span', rulerEl).forEach(t => { t.parentNode.classList.remove('nolabel'); if (t.scrollWidth > t.clientWidth + 1) t.parentNode.classList.add('nolabel'); });
  }
  new ResizeObserver(() => requestAnimationFrame(fitBands)).observe(rulerEl);

  function renderSteps() {
    stepsEl.innerHTML = run().steps.map((s, i) => `<li role="presentation"><button type="button" role="tab" id="rp-tab-${i}" aria-controls="rp-detail" aria-selected="false" tabindex="-1">` +
      `<span class="t">${s.t}${s.approx ? '(추정)' : ''}</span><span class="n">${s.n}</span><span class="d">${s.d || ''}</span></button></li>`).join('');
    $$('.lane, .lane-k', rulerEl).forEach(el => el.classList.toggle('on', el.dataset.run === runK));
  }
  // 시각 t에서 고른 기록의 마지막 단계를 고른다
  function setT(t) {
    select(stepAt(run().steps, t), false, t);
  }
  // 커서·슬라이더·시각 글만 갱신한다(소리를 따라갈 때 매 프레임 불리므로 단계 화면은 건드리지 않는다)
  function updateCursor() {
    const s = run().steps[at];
    $('#rp-cursor').style.left = L(curT);
    const slider = $('#rp-slider');
    slider.value = curT;
    const atText = `울음 시작 ${fmtT(curT)} · A ${phaseAt('A', curT)} · B ${phaseAt('B', curT)}`;
    $('#rp-at').textContent = atText;
    slider.setAttribute('aria-valuetext', `${atText}. 고른 기록 ${runK}: ${s.n}`);
  }
  function select(i, focus, t) {
    at = i;
    const s = run().steps[i];
    curT = t === undefined ? X(s.x) : t;
    $$('[role="tab"]', stepsEl).forEach((b, k) => { b.setAttribute('aria-selected', k === i); b.tabIndex = k === i ? 0 : -1; });
    if (focus) $('#rp-tab-' + i).focus();
    $('#rp-detail').setAttribute('aria-labelledby', 'rp-tab-' + i);
    const img = $('#rp-img');
    img.src = `img/${s.img}.jpg`;
    img.alt = `차 화면: ${s.cap}`;
    $('#rp-cap').textContent = `차 화면 캡처 · '${s.cap}'`;
    $('#rp-detail').innerHTML = `<p class="rp-time"><span>${s.t}${s.approx ? '(추정)' : ''}</span>${s.d ? `<b>울음 시작 ${s.d}</b>` : ''}</p><h3>${s.n}</h3><p class="rp-body">${s.body}</p>` +
      (s.log.length ? `<p class="rp-log">${s.log.map(l => `<code>${l}</code>`).join('')}</p>` : '') +
      (s.voice ? `<blockquote class="rp-voice"><small>음성 안내</small>${s.voice}</blockquote>` : '');
    updateCursor();
    $('#rp-state').textContent = S3D[s.s].say;
    if (rpView) rpView.set(S3D[s.s]);
    flowSync();
  }
  stepsEl.addEventListener('click', e => { const b = e.target.closest('[role="tab"]'); if (b) { stop(); select(+b.id.split('-').pop(), false); } });
  tabKeys(stepsEl, () => run().steps.length, () => at, i => { stop(); select(i, true); });
  const pickRun = radios($('#rp-runs'), b => { stop(); runK = b.dataset.run; renderSteps(); setT(curT); });
  rulerEl.addEventListener('click', e => {
    const b = e.target.closest('[data-step]');
    if (!b) return;
    stop();
    if (b.dataset.run !== runK) pickRun($(`#rp-runs [data-run="${b.dataset.run}"]`), false);
    select(+b.dataset.step, false);
  });

  // 재생: 정해진 간격으로 단계만 넘긴다(전환 효과 없음)
  function stop() { stopAudio(); if (!timer) return; clearInterval(timer); timer = 0; playBtn.setAttribute('aria-pressed', 'false'); playBtn.textContent = '차례로 보기'; }
  // 울음 소리 듣기: 재생 중에는 커서와 단계가 소리를 따라간다(울음 시작 = 소리의 0.89초)
  const audioEl = $('#rp-audio'), listenBtn = $('#rp-listen');
  let raf = 0;
  function follow() {
    const t = audioEl.currentTime - (window.CRY_WAVE ? CRY_WAVE.t0 : 0.89);
    const i = stepAt(run().steps, t);
    curT = Math.max(DOM0, Math.min(DOM1, t));
    if (i !== at) select(i, false, curT); else updateCursor();
    raf = requestAnimationFrame(follow);
  }
  function stopAudio() {
    cancelAnimationFrame(raf); raf = 0;
    if (!audioEl.paused) audioEl.pause();
    listenBtn.setAttribute('aria-pressed', 'false'); listenBtn.textContent = '울음 소리 듣기(12초)';
  }
  listenBtn.addEventListener('click', () => {
    if (raf) { stopAudio(); return; }
    stop();
    audioEl.currentTime = 0;  // 시작할 때마다 처음부터(멈춘 자리에서 이어 틀면 기록을 바꾼 뒤 커서가 튄다)
    audioEl.play().then(() => {
      listenBtn.setAttribute('aria-pressed', 'true'); listenBtn.textContent = '멈추기';
      raf = requestAnimationFrame(follow);
    }, stopAudio);
  });
  audioEl.addEventListener('ended', stopAudio);
  playBtn.addEventListener('click', () => {
    if (timer) { stop(); return; }
    stopAudio();  // 단계를 움직이는 주체는 하나만: 소리 따라가기를 끄고 자동 넘김을 켠다
    if (at >= run().steps.length - 1) select(0, false);
    playBtn.setAttribute('aria-pressed', 'true'); playBtn.textContent = '멈추기';
    timer = setInterval(() => { select(at + 1, false); if (at >= run().steps.length - 1) stop(); }, 2400);
  });
  onShow.push(id => { if (id !== 'overview') stop(); });
  $('#rp-screen').addEventListener('click', () => {
    const s = run().steps[at];
    vOpen([{ src: `img/${s.img}.jpg`, alt: `차 화면: ${s.cap}`, cap: `${s.t} ${s.n} · '${s.cap}'`, kind: 'car' }], 0);
  });
  mount3D($('#rp-stage'), 'cabin', v => { rpView = v; v.set(S3D[run().steps[at].s]); });
  afterLoad(() => {
    const io = new IntersectionObserver(es => {
      if (!es.some(e => e.isIntersecting)) return;
      io.disconnect();
      Object.keys(RUNS).forEach(k => RUNS[k].steps.forEach(s => { new Image().src = `img/${s.img}.jpg`; }));
    }, { rootMargin: '200px' });
    io.observe($('#rp-stage'));
  });
  /* ---------- 달래기 흐름 그림: 단계 log의 첫 코드에서 칸을 읽는다(손으로 적은 대응표 없음) ---------- */
  const FLOW_CODE = { LISTENING: 'L', ATTEMPT: 'A', GAP: 'G', CONFIRM: 'C', HANDOFF: 'H' };
  const FLOW_EDGE = { 'L>A': 'e1', 'A>G': 'e2', 'G>C': 'e3', 'C>L2': 'e4', 'G>A': 'r', 'C>A': 'r', 'G>H': 'h', 'C>H': 'h' };
  Object.keys(RUNS).forEach(k => {
    let prev = 'L', seenA = false;
    RUNS[k].nodes = RUNS[k].steps.map(st => {
      const c = FLOW_CODE[st.log[0]];  // 로그가 없거나 재생 오디오 같은 로그는 앞 단계의 칸을 잇는다
      let n = c || prev;
      if (c === 'L' && seenA) n = 'L2';  // 한 번 재생한 뒤의 듣기는 '다시 듣기'
      if (n === 'A') seenA = true;
      return (prev = n);
    });
  });
  const flowEl = $('#rp-flow');
  const edgeAt = (nodes, i) => (i > 0 && nodes[i - 1] !== nodes[i]) ? FLOW_EDGE[nodes[i - 1] + '>' + nodes[i]] : null;
  function flowSync() {
    const nodes = run().nodes, used = new Set(), seen = new Set(nodes);
    nodes.forEach((n, i) => { const e = edgeAt(nodes, i); if (e) used.add(e); });
    const cur = nodes[at], curE = edgeAt(nodes, at);
    $$('.fn', flowEl).forEach(b => {
      b.classList.toggle('on', b.dataset.n === cur);
      b.classList.toggle('off', !seen.has(b.dataset.n));
      b.disabled = !seen.has(b.dataset.n);
    });
    $$('[data-e]', flowEl).forEach(e => {  // 한 요소가 여러 길에 걸칠 수 있다(공통 줄기)
      const ks = e.dataset.e.split(' ');
      e.classList.toggle('off', !ks.some(k => used.has(k)));
      e.classList.toggle('on', ks.includes(curE));
    });
  }
  flowEl.addEventListener('click', e => {
    const b = e.target.closest('.fn');
    if (!b || b.disabled) return;
    stop();
    select(run().nodes.indexOf(b.dataset.n), false);
  });
  renderRuler();
  renderSteps();
  select(0, false);

  /* ---------- 차량·Pleos 기능 실측: 실내 투시 그림에 번호를 달고, 누르면 그 기능 상태와 기록을 보인다 ----------
     결과 글은 아래 '표로 보기'의 표와 같은 기록에서 옮겼다. */
  const R = (name, path, extra) => `<a href="${GH}${path}">${name}</a>${extra || ''}`;
  const DRIVE4 = '기어 D·60km/h에서 6/6 반영. 뒷좌석 시트 열선·뒷창 잠금·앞 공조 설정 온도·무드 조명 색 네 기능을 합쳐 <span class="nw">24/24</span>입니다(별도 실측 앱으로 잼).';
  const FEATS = [
    { n: '아기 자리', short: '아기 자리', anchor: 'seat', st: { light: 'listen', listen: true },
      res: '아기 설정에서 고르는 카시트 자리입니다. 실제 마이크 경로 실측(2026-11-13) 때는 기본값인 뒷좌석 오른쪽으로 두었습니다.', rec: R('blackhole_cry_1113', 'tools/measure/blackhole_cry_1113.txt', ' 1절') },
    { n: '뒷좌석 전용 재생', short: '뒷좌석 재생', anchor: 'Anchor_Speaker_RL', st: { light: 'soothe', ripple: true },
      res: '출력 장치를 지정하면 뒷좌석 존(<code>bus100_audio_zone_1</code>)으로, 지정하지 않으면 기본 출력으로 나갑니다.', rec: R('rear_audio_1102', 'tools/pleos-probe/rear_audio_1102.txt') },
    { n: '뒷좌석 시트 열선', short: '시트 열선', anchor: 'Anchor_Heat_RL', st: { callouts: ['heat'] }, res: DRIVE4, rec: R('babycare_Drive', 'tools/sim/babycare_Drive.txt') },
    { n: '뒷창 잠금', short: '뒷창 잠금', anchor: 'Anchor_Window_RL', st: { callouts: ['lock'] }, res: DRIVE4, rec: R('babycare_Drive', 'tools/sim/babycare_Drive.txt') },
    { n: '앞 공조 설정 온도', short: '공조', anchor: 'Seat_F_L_Top', box: true, st: {}, res: DRIVE4, rec: R('babycare_Drive', 'tools/sim/babycare_Drive.txt') },
    { n: '무드 조명', short: '무드 조명', anchor: 'Ambient_Lines', st: { light: 'soothe' },
      res: '무드 조명 색은 기어 D·60km/h에서 6/6 반영(위 네 기능 24/24에 포함). 밝기는 주행 6/6, 정차 6/6(쓰고 0.5초 뒤 다시 읽어 확인)입니다(별도 실측 앱으로 잼).',
      rec: R('babycare_Drive', 'tools/sim/babycare_Drive.txt', ', ') + R('official_audit_1108', 'tools/measure/official_audit_1108.txt') },
    { n: '뒷좌석 독서등', short: '독서등', anchor: 'Anchor_Lamp_R', st: {},
      res: '정차 6/6, 주행 중 0/6. 주행 중에는 시스템이 명령을 거절합니다(오류 50001).', rec: R('babycare_Park', 'tools/sim/babycare_Park.txt', ', ') + R('babycare_Drive', 'tools/sim/babycare_Drive.txt') }
  ];
  const ftList = $('#ft-list'), ftSpots = $('#ft-spots');
  let ftAt = 0, ftView = null;
  const spotNo = {};
  let k = 0;
  FEATS.forEach((f, i) => { if (f.anchor) spotNo[i] = ++k; });
  ftList.innerHTML = FEATS.map((f, i) => `<li role="presentation"><button type="button" role="tab" id="ft-tab-${i}" aria-controls="ft-panel" aria-selected="false" tabindex="-1">` +
    `<span class="no">${spotNo[i]}</span>${f.n}</button></li>`).join('');
  ftSpots.innerHTML = FEATS.map((f, i) => f.anchor ? `<button type="button" class="spot" data-i="${i}" tabindex="-1"><b>${spotNo[i]}</b><span>${f.short}</span></button>` : '').join('');
  // 위치가 없는 모양 노드(원점에 놓인 메쉬)는 모양의 상자 가운데를 기준점으로 쓴다(box: true)
  function spotPt(f) {
    if (!f.box) return ftView.project(f.anchor);
    const o = ftView.S && ftView.S.root.getObjectByName(f.anchor);
    if (!o) return ftView.project(f.anchor);
    f.c = f.c || new THREE.Box3().setFromObject(o).getCenter(new THREE.Vector3());
    const v = f.c.clone().project(ftView.camera);
    return { x: (v.x + 1) / 2 * ftView.host.clientWidth, y: (1 - v.y) / 2 * ftView.host.clientHeight, z: v.z };
  }
  function placeSpots() {
    if (!ftView) return;
    const W = ftSpots.clientWidth, H = ftSpots.clientHeight, pts = [];
    $$('.spot', ftSpots).forEach(b => {
      const p = spotPt(FEATS[+b.dataset.i]);
      const ok = p && p.z < 1 && p.x > 0 && p.y > 0 && p.x < W && p.y < H;
      b.hidden = !ok;
      if (ok) pts.push({ b, x: p.x, y: p.y });
    });
    // 투영한 번호가 서로 겹치면(좁은 화면, 돌린 각도) 겹친 만큼 반씩 떼어 놓는다. 매 프레임 투영값에서 새로 계산한다
    for (let it = 0; it < 3; it++) {
      for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < pts.length; j++) {
        const a = pts[i], c = pts[j], min = a.b.firstElementChild.offsetWidth + 2;
        let dx = c.x - a.x, dy = c.y - a.y, d = Math.hypot(dx, dy);
        if (d >= min) continue;
        if (!d) { dx = 1; dy = 0; d = 1; }
        const m = (min - d) / 2;
        a.x -= dx / d * m; a.y -= dy / d * m; c.x += dx / d * m; c.y += dy / d * m;
      }
    }
    pts.forEach(q => {
      const r = q.b.firstElementChild.offsetWidth / 2;
      q.b.style.transform = `translate(${Math.min(Math.max(q.x, r), W - r).toFixed(1)}px, ${Math.min(Math.max(q.y, r), H - r).toFixed(1)}px)`;
    });
  }
  function ftSelect(i, focus) {
    ftAt = i;
    const f = FEATS[i];
    $$('[role="tab"]', ftList).forEach((b, j) => { b.setAttribute('aria-selected', j === i); b.tabIndex = j === i ? 0 : -1; });
    if (focus) $('#ft-tab-' + i).focus();
    $$('.spot', ftSpots).forEach(b => b.classList.toggle('on', +b.dataset.i === i));
    $('#ft-panel').setAttribute('aria-labelledby', 'ft-tab-' + i);
    $('#ft-panel').innerHTML = `<h3>${f.n}</h3><p>${f.res}</p><p class="src">기록: ${f.rec}</p>`;
    if (!ftView) return;
    ftView.set(Object.assign({ light: 'base', listen: false, ripple: false, callouts: [] }, f.st));
  }
  ftList.addEventListener('click', e => { const b = e.target.closest('[role="tab"]'); if (b) ftSelect(+b.id.split('-').pop(), false); });
  ftSpots.addEventListener('click', e => { const b = e.target.closest('.spot'); if (b) ftSelect(+b.dataset.i, false); });
  tabKeys(ftList, () => FEATS.length, () => ftAt, i => ftSelect(i, true));
  mount3D($('#ft-stage'), 'plan', v => { ftView = v; v.onRender = placeSpots; ftSelect(ftAt, false); });
  ftSelect(0, false);

  /* ---------- 검증 결과 요약 띠: 근거 종류 표식은 각 절 kicker 글에서 읽어 만든다 ---------- */
  const EV = [['에뮬레이터 실측', 'emu'], ['맥', 'mac'], ['시뮬레이션', 'sim']];
  const evOf = el => $('.kicker', el) ? $('.kicker', el).textContent.split(' · ').map(t => (EV.find(e => t.startsWith(e[0])) || [])[1]).filter(Boolean) : [];
  const evHTML = kinds => kinds.map(k => `<i class="ev ev-${k}" aria-hidden="true"></i>`).join('');
  $$('[data-page="results"] article.fig').forEach(a => { const k = $('.kicker', a); if (k) k.insertAdjacentHTML('afterbegin', evHTML(evOf(a))); });
  $$('.sumbar a').forEach(a => { $('.sk', a).insertAdjacentHTML('afterbegin', evHTML(evOf($('#' + a.dataset.to)))); });
  $('#sum-limits').textContent = $$('#unknown .limits li').length + '가지';
  $('.sumbar').addEventListener('click', e => {
    const a = e.target.closest('a[data-to]');
    if (!a) return;
    e.preventDefault();  // 주소창의 #results는 그대로 둔다
    $('#' + a.dataset.to).scrollIntoView();
  });

  // 테마가 바뀌면 3D의 투시선 색을 다시 맞춘다(그림은 CSS 변수 색이라 저절로 바뀐다)
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (rpView) rpView.set(S3D[run().steps[at].s]);
    if (ftView) ftSelect(ftAt, false);
  });

  /* ---------- 인쇄·PDF: 인쇄 때 모든 메뉴가 펼쳐지므로, 이미 읽은 그림 라이브러리로 아직 안 그린 그림을 그려 둔다(새로 읽지 않는다) ---------- */
  window.addEventListener('beforeprint', () => {
    document.documentElement.classList.add('printing');  // 숨은 메뉴도 너비가 있어야 그릴 수 있다
    $$('img[loading="lazy"]').forEach(i => { i.loading = 'eager'; });  // 늦게 받는 그림이 빈 상자로 찍히지 않게 인쇄 직전에 받기 시작한다
    if (plotOK) drawCharts();
    renderCG();
  });
  window.addEventListener('afterprint', () => document.documentElement.classList.remove('printing'));

  show();
})();
