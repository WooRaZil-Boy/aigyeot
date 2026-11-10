import SwiftUI

private typealias DS = DesignSystem

// 차트(시안 js/charts.js·css/charts.css): 오늘 하루 띠·7일 막대·잠 패턴 히트맵·에피소드 띠·방법별 점 행렬·조향 비교.
// 값은 ChartData가 계산하고 여기는 그리기만 한다. 안드로이드와 같은 Canvas 그림이라 두 앱 모양이 같다.
// 툴팁: 표시를 누르면 값 말풍선(먹색 바탕). 처음 보일 때 한 번: 막대가 자라고 띠는 왼쪽에서 그려진다(동작 줄이기면 없음).
// 화면 읽기는 차트마다 한 줄 요약(값은 차트 머리·숫자 줄에도 있다).

// MARK: 공통: 누른 자리의 툴팁과 처음 등장

/// 누를 수 있는 표시 하나(차트 좌표의 사각형과 말).
struct ChartHit: Equatable {
    let rect: CGRect
    let tip: String
    /// 점 표시(하루 띠의 기저귀 점·울음 마름모). 칸과 함께 닿으면 점이 먼저다(좁은 탑승 칸 안의 마름모도 눌리게, 안드로이드와 같다).
    var point = false

    /// 누른 자리 `p`의 표시. 닿는 후보(담거나 6pt 안) 중 점이 있으면 점만, 없으면 칸에서 고른다:
    /// 가장 가까운 것(담으면 0), 같으면 면적이 작은 것, 그래도 같으면 가운데가 가까운 것. 닿는 것이 없으면 nil.
    static func pick(_ hits: [ChartHit], at p: CGPoint) -> ChartHit? {
        func dist(_ r: CGRect) -> CGFloat { hypot(max(r.minX - p.x, 0, p.x - r.maxX), max(r.minY - p.y, 0, p.y - r.maxY)) }
        func key(_ h: ChartHit) -> (CGFloat, CGFloat, CGFloat) {
            (dist(h.rect), h.rect.width * h.rect.height, hypot(h.rect.midX - p.x, h.rect.midY - p.y))
        }
        let near = hits.filter { dist($0.rect) <= 6 }
        let pool = near.contains(where: \.point) ? near.filter(\.point) : near
        return pool.min { key($0) < key($1) }
    }
}

/// 차트 위 툴팁: 누른 표시 위에 말풍선. 다시 누르거나 빈 곳을 누르면 닫는다.
private struct TipOverlay: ViewModifier {
    let hits: [ChartHit]
    @State private var shown: ChartHit?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { p in
                let hit = ChartHit.pick(hits, at: p)
                shown = hit == shown ? nil : hit
            }
            .overlay {
                if let s = shown {
                    TipPlacer(anchor: s.rect) {
                        Text(s.tip).typeStyle(.tag).foregroundStyle(DS.surface)
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(DS.textPrimary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .fixedSize()
                    }
                    .transition(reduceMotion ? .identity : .opacity.combined(with: .offset(y: 4)))   // 시안 .ch-tip: 아래 4에서 120ms linear(동작 줄이기면 바로)
                    .allowsHitTesting(false)
                }
            }
            .animation(reduceMotion ? nil : DS.Motion.tip, value: shown)
            .onChange(of: hits) { _, _ in shown = nil }   // 기록이 바뀌면(지움·진행 중 시간) 옛 값 툴팁을 닫는다
    }
}

extension View {
    func chartTips(_ hits: [ChartHit]) -> some View { modifier(TipOverlay(hits: hits)) }
}

/// 말풍선을 누른 표시 바로 위 가운데에 놓는다(차트 폭 안으로, 위로 넘치면 0).
private struct TipPlacer: Layout {
    let anchor: CGRect
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize { proposal.replacingUnspecifiedDimensions() }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let v = subviews.first else { return }
        let sz = v.sizeThatFits(.unspecified)
        let x = min(max(4, anchor.midX - sz.width / 2), max(4, bounds.width - sz.width - 4))
        let y = max(0, anchor.minY - sz.height - 8)
        v.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + y), proposal: .unspecified)
    }
}

/// 처음 보일 때 0 → 1(동작 줄이기면 바로 1).
private struct Appear: ViewModifier {
    let animation: Animation
    @Binding var t: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.onAppear {
            guard t == 0 else { return }
            if reduceMotion { t = 1 } else { withAnimation(animation) { t = 1 } }
        }
    }
}

/// 띠·히트맵·에피소드: 0.9초 cubic(.65,0,.35,1)로 왼쪽에서 그려진다(시안 charts.js grow).
private let revealCurve = DS.Motion.chartReveal
/// 막대: 막대마다 0.6초 cubic(.2,.8,.2,1)로 바닥에서 자라고, 0.04초씩 늦게 시작한다(8개마다 다시). t는 전체 시간에 걸쳐 고르게 흐르고
/// 막대마다 곡선은 `barGrow`가 건다.
private let growStep = DS.Motion.chartBarStagger, growEach = DS.Motion.chartBarSeconds, growTotal = growEach + growStep * 7
private let growCurve = Animation.linear(duration: growTotal)
private let growEase = DS.Motion.curve("enter")
private func barGrow(_ t: Double, index i: Int) -> Double {
    let local = min(1, max(0, (t * growTotal - growStep * Double(i % 8)) / growEach))
    return growEase.value(at: local)
}

/// 진행 값을 프레임마다 받는 Canvas(Canvas는 그 자체로 애니메이션 값을 보간하지 않는다).
private struct GrowCanvas: View, Animatable {
    var t: Double
    let draw: (GraphicsContext, CGSize, Double) -> Void
    var animatableData: Double { get { t } set { t = newValue } }
    var body: some View { Canvas { ctx, size in draw(ctx, size, t) } }
}

private extension GraphicsContext {
    /// 글자를 그린다(앵커 기준). 크기를 돌려준다.
    @discardableResult
    func label(_ s: String, at p: CGPoint, anchor: UnitPoint, size: CGFloat, weight: CGFloat, color: Color, halo: Color? = nil) -> CGSize {
        let t = resolve(Text(s).font(DS.fixedFont(size, weight, monospacedDigits: true)).foregroundStyle(color))
        let m = t.measure(in: CGSize(width: 1000, height: 200))
        if let halo {
            let r = CGRect(x: p.x - m.width * anchor.x - 3, y: p.y - m.height * anchor.y - 1, width: m.width + 6, height: m.height + 2)
            fill(Path(roundedRect: r, cornerRadius: 3), with: .color(halo))
        }
        draw(t, at: p, anchor: anchor)
        return m
    }

    /// 시간 눈금 0·6·12·18·24시(오늘 하루 띠·잠 패턴 같은 규칙). 앞 눈금과 4pt 안으로 붙거나 `avoid`(지금 글자)와 겹치면 뺀다.
    /// 큰 글자에서 '18시24시'처럼 붙지 않게(안드로이드와 같다).
    func hourTicks(x: (Int) -> CGFloat, y: CGFloat, size: CGFloat, avoid: ClosedRange<CGFloat>? = nil) {
        var prevHi = -CGFloat.infinity
        for (h, a) in [(0, UnitPoint.bottomLeading), (6, .bottom), (12, .bottom), (18, .bottom), (24, .bottomTrailing)] {
            let text = "\(h)시"
            let tw = resolve(Text(text).font(DS.fixedFont(size, 500, monospacedDigits: true))).measure(in: CGSize(width: 200, height: 100)).width
            let lo = x(h) - tw * a.x, hi = lo + tw
            if lo < prevHi + 4 { continue }
            if let avoid, hi > avoid.lowerBound, lo < avoid.upperBound { continue }
            label(text, at: CGPoint(x: x(h), y: y), anchor: a, size: size, weight: 500, color: DS.textTertiary)
            prevHi = hi
        }
    }
}

// MARK: 오늘 하루 띠

/// 줄마다 위에 '이름 · 합계', 그 아래 0~24시 회색 트랙에 표시. 지금 뒤는 흐리게, 지금 선은 모든 줄을 가로지른다.
private struct DayBandChartBody: View {
    let data: ChartData.Day
    @ScaledMetric(relativeTo: .footnote) private var fs: CGFloat = 13
    @State private var t: Double = 0

    private var k: CGFloat { fs / 13 }
    private var th: CGFloat { (18 * k).rounded() }
    private var hh: CGFloat { (fs * 1.35).rounded() }
    private var pad: CGFloat { (7 * k).rounded() }
    private var gap: CGFloat { (16 * k).rounded() }
    private var top: CGFloat { (fs * 0.4).rounded() }
    private var step: CGFloat { hh + pad + th + gap }
    private func headY(_ i: Int) -> CGFloat { top + CGFloat(i) * step }
    private func trY(_ i: Int) -> CGFloat { headY(i) + hh + pad }
    private var bottom: CGFloat { trY(max(0, data.lanes.count - 1)) + th }
    private var height: CGFloat { bottom + (10 * k).rounded() + fs + 6 }

    var body: some View {
        GeometryReader { g in
            Canvas { ctx, size in draw(ctx, size) }
                .chartTips(hits(width: g.size.width))
        }
        .frame(height: height)
        .mask(alignment: .leading) { GeometryReader { g in Rectangle().frame(width: g.size.width * t) } }
        .modifier(Appear(animation: revealCurve, t: $t))
        .accessibilityElement()
        .accessibilityLabel(data.summary)
    }

    private func x(_ m: Int, _ w: CGFloat) -> CGFloat { w * CGFloat(m) / 1440 }

    private func color(_ kind: ChartData.LaneKind) -> Color {
        switch kind { case .sleep: DS.cSleep; case .feed: DS.cFeed; case .diaper: DS.cDiaper; case .car: DS.cCar }
    }

    private func spanRect(_ s: ChartData.Span, lane i: Int, w: CGFloat) -> CGRect {
        CGRect(x: x(s.from, w), y: trY(i), width: max(6 * k, x(s.to, w) - x(s.from, w) - 2 * k), height: th)
    }

    private func hits(width w: CGFloat) -> [ChartHit] {
        data.lanes.enumerated().flatMap { i, l in
            l.spans.map { ChartHit(rect: spanRect($0, lane: i, w: w), tip: $0.tip) }
                + l.marks.map { m in ChartHit(rect: CGRect(x: x(m.at, w) - th / 2, y: trY(i), width: th, height: th), tip: m.tip, point: true) }
        }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        let w = size.width, rx = (4 * k).rounded()
        // 6·12·18시 세로선: 줄 사이 틈에서만 보인다(트랙이 위에 덮음)
        for h in [6, 12, 18] {
            var p = Path(); p.move(to: CGPoint(x: x(h * 60, w), y: trY(0))); p.addLine(to: CGPoint(x: x(h * 60, w), y: bottom))
            ctx.stroke(p, with: .color(DS.line), lineWidth: 1)
        }
        for i in data.lanes.indices {
            ctx.fill(Path(roundedRect: CGRect(x: 0, y: trY(i), width: w, height: th), cornerRadius: th / 2), with: .color(DS.surfaceRaised))
        }
        for (i, l) in data.lanes.enumerated() {
            let c = color(l.kind)
            let fill: Color = l.kind == .car ? DS.cCar.opacity(0.22) : c   // 탑승 구간은 트랙 안 연한 주황 칸
            for s in l.spans { ctx.fill(Path(roundedRect: spanRect(s, lane: i, w: w), cornerRadius: rx), with: .color(fill)) }
            let cy = trY(i) + th / 2
            for m in l.marks {
                let cx = x(m.at, w)
                if l.kind == .car {   // 차에서 울음: 바탕색 테를 두른 마름모
                    let q = th * 0.42
                    var p = Path()
                    p.move(to: CGPoint(x: cx, y: cy - q)); p.addLine(to: CGPoint(x: cx + q, y: cy)); p.addLine(to: CGPoint(x: cx, y: cy + q)); p.addLine(to: CGPoint(x: cx - q, y: cy)); p.closeSubpath()
                    ctx.stroke(p, with: .color(DS.surface), lineWidth: 2)
                    ctx.fill(p, with: .color(c))
                } else {
                    let r = th * 0.3
                    ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)), with: .color(c))
                }
            }
        }
        let fs13 = fs
        let ay = size.height - 2
        if let now = data.nowMin {
            let nx = x(now, w)
            // 지금 뒤: 트랙만 흐리게(머리 글자는 그대로)
            for i in data.lanes.indices {
                ctx.fill(Path(CGRect(x: nx + 1, y: trY(i) - 1, width: max(0, w - nx - 1), height: th + 2)), with: .color(DS.surface.opacity(0.6)))
            }
            var p = Path(); p.move(to: CGPoint(x: nx, y: trY(0) - 3 * k)); p.addLine(to: CGPoint(x: nx, y: bottom + 5 * k))
            ctx.stroke(p, with: .color(DS.textPrimary), lineWidth: 1.5)
            // 아래 시간 눈금: '지금'과 겹치는 눈금은 뺀다
            let nowLab = "지금 " + ChartData.hhmm(now)
            let nowW = ctx.resolve(Text(nowLab).font(DS.fixedFont(fs13, 700, monospacedDigits: true))).measure(in: CGSize(width: 400, height: 100)).width
            let nowX = min(w - nowW / 2, max(nowW / 2, nx))
            ctx.hourTicks(x: { x($0 * 60, w) }, y: ay, size: fs, avoid: (nowX - nowW / 2 - 4)...(nowX + nowW / 2 + 4))
            ctx.label(nowLab, at: CGPoint(x: nowX, y: ay), anchor: .bottom, size: fs13, weight: 700, color: DS.textPrimary)
        } else {
            ctx.hourTicks(x: { x($0 * 60, w) }, y: ay, size: fs)
        }
        // 줄 머리: 왼쪽 점·이름, 오른쪽 부가 · 값. 지금 선 위에 바탕색 테(halo)로 그려 선이 글자를 긋지 않는다.
        let dot = max(4, fs * 0.3)
        for (i, l) in data.lanes.enumerated() {
            let base = headY(i) + hh / 2
            ctx.fill(Path(ellipseIn: CGRect(x: 0, y: base - dot, width: dot * 2, height: dot * 2)), with: .color(color(l.kind)))
            ctx.label(l.name, at: CGPoint(x: dot * 2 + 6 * k, y: base), anchor: .leading, size: fs, weight: 600, color: DS.textPrimary, halo: DS.surface)
            let vs = ctx.label(l.value, at: CGPoint(x: w, y: base), anchor: .trailing, size: fs + 2 * k, weight: 700, color: DS.textPrimary, halo: DS.surface)
            if let sub = l.sub {
                ctx.label(sub, at: CGPoint(x: w - vs.width - 6 * k, y: base), anchor: .trailing, size: fs, weight: 500, color: DS.textTertiary, halo: DS.surface)
            }
        }
    }

}

// MARK: 7일 막대(수유 간격·차에서 울음)·조향 비교

/// 막대 24 이하·위 4 둥근 끝·바닥 각짐. 오늘(마지막)만 데이터 색, 나머지 회색. 기준선(평소)과 오늘 값 라벨.
struct WeekBarsChartBody: View {
    struct Item: Equatable { let label: String; let value: Double?; let tip: String?; var hot = false; var valueLabel: String? = nil; var dim = false }
    let items: [Item]
    let hot: Color
    var target: (value: Double, label: String)? = nil
    var height: CGFloat = 150
    let summary: String
    @ScaledMetric(relativeTo: .caption) private var fs: CGFloat = 11
    @State private var t: Double = 0

    var body: some View {
        GeometryReader { g in
            GrowCanvas(t: t) { ctx, size, t in draw(ctx, size, t: t) }
                .chartTips(layout(g.size).compactMap { b in b.item.tip.map { ChartHit(rect: b.slot, tip: $0) } })
        }
        .frame(height: height)
        .modifier(Appear(animation: growCurve, t: $t))
        .accessibilityElement()
        .accessibilityLabel(fullSummary)
    }

    private struct Placed { let item: Item; let bar: CGRect; let slot: CGRect }

    /// 화면 읽기: 차트 이름 + 막대마다 값(툴팁 글, 값이 없으면 '기록 없음').
    private var fullSummary: String { summary + ": " + items.map { $0.tip ?? "\($0.label) \(CareText.noRecords)" }.joined(separator: ", ") }

    private var maxValue: Double { max(items.compactMap(\.value).max() ?? 0, target?.value ?? 0, 0.0001) * 1.12 }

    private func layout(_ size: CGSize) -> [Placed] {
        let top = 20 + fs, base = size.height - fs - 12, n = CGFloat(items.count), slot = (size.width - 8) / n, bw = min(22, slot * 0.6)
        return items.enumerated().map { i, it in
            let cx = 4 + slot * CGFloat(i) + slot / 2
            let h = CGFloat((it.value ?? 0) / maxValue) * (base - top)
            return Placed(item: it, bar: CGRect(x: cx - bw / 2, y: base - h, width: bw, height: h), slot: CGRect(x: cx - slot / 2, y: top - 10, width: slot, height: base - top + 10))
        }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize, t: Double) {
        let top = 20 + fs, base = size.height - fs - 12
        var axis = Path(); axis.move(to: CGPoint(x: 0, y: base)); axis.addLine(to: CGPoint(x: size.width, y: base))
        ctx.stroke(axis, with: .color(DS.line), lineWidth: 1)
        if let target {
            let y = base - CGFloat(target.value / maxValue) * (base - top)
            var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
            ctx.stroke(p, with: .color(DS.textTertiary), lineWidth: 1)
            ctx.label(target.label, at: CGPoint(x: 0, y: y - 4), anchor: .bottomLeading, size: fs, weight: 600, color: DS.textTertiary)
        }
        for (i, b) in layout(size).enumerated() {
            let h = b.bar.height * CGFloat(barGrow(t, index: i))
            if b.item.value != nil, h > 0 {
                let r = min(4, h / 2)
                let rect = CGRect(x: b.bar.minX, y: base - h, width: b.bar.width, height: h)
                let shape = UnevenRoundedRectangle(topLeadingRadius: r, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: r).path(in: rect)
                ctx.fill(shape, with: .color(b.item.hot ? hot : DS.textPrimary.opacity(0.13)))
            }
            ctx.label(b.item.label, at: CGPoint(x: b.bar.midX, y: size.height - 3), anchor: .bottom, size: fs, weight: b.item.hot ? 700 : 500,
                      color: b.item.hot ? DS.textPrimary : DS.textTertiary)
            if let v = b.item.valueLabel, b.item.value != nil {
                ctx.label(v, at: CGPoint(x: b.item.hot ? b.bar.maxX : b.bar.midX, y: base - b.bar.height - 7), anchor: b.item.hot ? .bottomTrailing : .bottom,
                          size: fs + 1, weight: b.item.dim ? 600 : 700, color: b.item.dim ? DS.textTertiary : DS.textPrimary)
            }
        }
    }
}

// MARK: 잠 패턴 히트맵

/// 7일 × 48칸(30분), 잔 칸만 잠 색. 눈금 0·6·12·18·24시(차트 안 24시간제).
private struct SleepHeatChartBody: View {
    let grid: ChartData.SleepGrid
    @ScaledMetric(relativeTo: .caption) private var fs: CGFloat = 11
    @ScaledMetric(relativeTo: .caption) private var lead: CGFloat = 34
    @State private var t: Double = 0
    private var ch: CGFloat { max(14, (fs * 1.3).rounded()) }   // 요일 글자가 줄 높이를 넘지 않게

    private var height: CGFloat { CGFloat(grid.rows.count) * (ch + 2) + fs + 8 }

    var body: some View {
        GeometryReader { g in
            Canvas { ctx, size in draw(ctx, size) }
                .chartTips(hits(g.size.width))
        }
        .frame(height: height)
        .mask(alignment: .leading) { GeometryReader { g in Rectangle().frame(width: g.size.width * t) } }
        .modifier(Appear(animation: revealCurve, t: $t))
        .accessibilityElement()
        .accessibilityLabel(grid.summary)
    }

    private func cw(_ w: CGFloat) -> CGFloat { (w - lead) / 48 }

    private func hits(_ w: CGFloat) -> [ChartHit] {
        grid.rows.enumerated().flatMap { r, row in
            row.enumerated().compactMap { c, on in
                on ? ChartHit(rect: CGRect(x: lead + CGFloat(c) * cw(w), y: CGFloat(r) * (ch + 2), width: cw(w), height: ch),
                              tip: "\(ChartData.monthDay(grid.days[r])) \(ChartData.hhmm(c * 30)) 잠") : nil
            }
        }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        let w = size.width, cw = cw(w)
        for (r, row) in grid.rows.enumerated() {
            let y = CGFloat(r) * (ch + 2)
            let today = r == grid.rows.count - 1
            ctx.label(grid.labels[r], at: CGPoint(x: 0, y: y + ch / 2), anchor: .leading, size: fs, weight: today ? 700 : 500, color: today ? DS.textPrimary : DS.textTertiary)
            for (c, on) in row.enumerated() {
                let rect = CGRect(x: lead + CGFloat(c) * cw, y: y, width: max(0.5, cw - 1.2), height: ch)
                ctx.fill(Path(roundedRect: rect, cornerRadius: 1.5), with: .color(on ? DS.cSleep : DS.surfaceRaised))
            }
        }
        ctx.hourTicks(x: { lead + CGFloat($0 * 2) * cw }, y: size.height - 1, size: fs)
    }
}

// MARK: 에피소드 띠

/// 소리 줄(방법 이름, 먹색 칸)·재생 사이(회색, 이름 없이 시간만)·표시(감지·다시 울음·쉼터 제안·그쳤어요). 리포트에 울음 구간이 없어 울음 줄은 그리지 않는다.
private struct EpisodeBandChartBody: View {
    let band: ChartData.Episode
    let summary: String
    @ScaledMetric(relativeTo: .caption) private var fs: CGFloat = 11
    @ScaledMetric(relativeTo: .caption) private var lead: CGFloat = 40
    @State private var t: Double = 0
    private let lh: CGFloat = 30

    private var height: CGFloat { lh + 18 + (fs + 6) * 2 }

    var body: some View {
        GeometryReader { g in
            Canvas { ctx, size in draw(ctx, size) }
                .chartTips(hits(g.size.width))
        }
        .frame(height: height)
        .mask(alignment: .leading) { GeometryReader { g in Rectangle().frame(width: g.size.width * t) } }
        .modifier(Appear(animation: revealCurve, t: $t))
        .accessibilityElement()
        .accessibilityLabel(summary)
    }

    private func x(_ s: Int, _ w: CGFloat) -> CGFloat { lead + (w - 6 - lead) * CGFloat(s) / CGFloat(max(1, band.totalS)) }

    private func rect(_ s: ChartData.Seg, _ w: CGFloat) -> CGRect { CGRect(x: x(s.from, w), y: 2, width: max(2, x(s.to, w) - x(s.from, w) - 2), height: lh - 8) }

    private func hits(_ w: CGFloat) -> [ChartHit] {
        (band.sounds + band.gaps).map { ChartHit(rect: rect($0, w), tip: $0.tip) }
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize) {
        let w = size.width
        ctx.label("소리", at: CGPoint(x: 0, y: 2 + (lh - 8) / 2), anchor: .leading, size: fs + 1, weight: 600, color: DS.textSecondary)
        ctx.fill(Path(roundedRect: CGRect(x: lead, y: 2, width: w - 6 - lead, height: lh - 8), cornerRadius: 4), with: .color(DS.surfaceRaised))
        for g in band.gaps { ctx.fill(Path(roundedRect: rect(g, w), cornerRadius: 4), with: .color(DS.surfaceRaised)) }
        for s in band.sounds {
            let r = rect(s, w)
            ctx.fill(Path(roundedRect: r, cornerRadius: 4), with: .color(DS.textPrimary))
            let t = ctx.resolve(Text(s.name ?? "").font(DS.fixedFont(fs, 600)).foregroundStyle(DS.surface))
            let m = t.measure(in: CGSize(width: 400, height: 100))
            if r.width > m.width + 16 { ctx.draw(t, at: CGPoint(x: r.minX + 8, y: r.midY), anchor: .leading) }
        }
        for (i, m) in band.marks.enumerated() {
            let mx = min(w - 6, x(m.at, w))
            var p = Path(); p.move(to: CGPoint(x: mx, y: 0)); p.addLine(to: CGPoint(x: mx, y: lh - 2))
            ctx.stroke(p, with: .color(DS.textPrimary.opacity(0.18)), lineWidth: 1)
            ctx.fill(Path(ellipseIn: CGRect(x: mx - 4, y: lh, width: 8, height: 8)), with: .color(DS.textPrimary))
            let anchor: UnitPoint = i == 0 ? .bottomLeading : i == band.marks.count - 1 ? .bottomTrailing : .bottom
            ctx.label(m.label, at: CGPoint(x: mx, y: lh + 10 + fs + 4 + CGFloat(i % 2) * (fs + 6)), anchor: anchor, size: fs, weight: 600, color: DS.textSecondary)
        }
    }
}

// MARK: 방법별 성적 점 행렬

/// 튼 횟수만큼 점, 그친 만큼 먹색 채움. 3번 미만은 '아직 모름'(시안 Charts.methods).
struct MethodDots: View {
    struct Row: Identifiable {
        let id: Int, name: String, ok: Int, n: Int
        /// 리포트 값은 차가 보낸 그대로라 음수·뒤집힌 값이 올 수 있다(점 범위가 깨지지 않게 0 ≤ ok ≤ n).
        init(id: Int, name: String, ok: Int, n: Int) {
            self.id = id
            self.name = name
            self.n = max(0, n)
            self.ok = min(max(0, ok), max(0, n))
        }
    }
    let rows: [Row]
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(rows) { m in
                let base = rows.prefix { $0.id != m.id }.reduce(0) { $0 + $1.ok }   // 앞 줄들의 찬 점 수: 점은 모든 줄을 이어 0.02초씩 늦게 나온다
                let known = m.n >= CareStats.methodKnownAttempts
                Group {
                    // 큰 글자, 또는 이름을 80으로 줄여도 한 줄에 점·횟수가 들어가지 않는 좁은 폭(320pt 등)이면 이름·값 한 줄, 그 아래 점
                    if typeSize.isAccessibilitySize { stacked(m, known: known, base: base) } else {
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 12) {
                                WidthIn(lo: 80, hi: 144) { name(m, known: known) }   // 긴 부모 노래 이름은 줄을 바꾸고 점·횟수 자리를 남긴다(안드로이드 widthIn 80~144)
                                dots(m, known: known, base: base)
                                Spacer(minLength: 4)
                                value(m, known: known)
                            }
                            stacked(m, known: known, base: base)
                        }
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(known ? "\(m.name): \(m.n)번 틀어서 \(m.ok)번 그침" : "\(m.name): 아직 모름, \(m.n)번 틀었어요")
            }
        }
        .onAppear { shown = true }
    }

    private func stacked(_ m: Row, known: Bool, base: Int) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { name(m, known: known); Spacer(minLength: 8); value(m, known: known) }
            dots(m, known: known, base: base)
        }
    }

    /// 자식 너비를 lo~hi로 묶는다: 한 줄 너비가 hi를 넘으면 hi에서 줄을 바꾸고, 짧으면 lo를 채운다(안드로이드 Modifier.widthIn).
    /// 이상 크기(제안 없음)는 lo다: ViewThatFits가 '이름을 lo까지 줄이면 한 줄에 들어가는지'로 고르게.
    private struct WidthIn: Layout {
        let lo: CGFloat, hi: CGFloat
        func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
            let one = subviews[0].sizeThatFits(.unspecified).width
            let w = min(hi, max(lo, min(one, proposal.width ?? lo)))
            return CGSize(width: w, height: subviews[0].sizeThatFits(ProposedViewSize(width: w, height: nil)).height)
        }
        func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
            subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
        }
    }

    private func name(_ m: Row, known: Bool) -> some View {
        Text(m.name).typeStyle(.chip).foregroundStyle(known ? DS.textPrimary : DS.textTertiary)
    }

    @ViewBuilder private func value(_ m: Row, known: Bool) -> some View {
        if known {
            (Text("\(m.ok)").font(DS.font(.strong)).foregroundStyle(DS.textPrimary) + Text("/\(m.n)번").font(DS.font(.meta)).foregroundStyle(DS.textTertiary))
                .monospacedDigit()
        } else {
            Text("아직 모름").typeStyle(.meta).foregroundStyle(DS.textTertiary)
        }
    }

    /// 찬 점은 처음 보일 때 0 → 1로 튄다(시안 charts.js: spring 강성 600·감쇠 30, 점마다 0.02초 늦게). 동작 줄이기면 바로.
    private func dots(_ m: Row, known: Bool, base: Int) -> some View {
        // 점이 많으면 줄을 넘긴다(한 줄 10개)
        let cols = Array(repeating: GridItem(.fixed(12), spacing: 5), count: min(10, max(1, m.n)))
        return LazyVGrid(columns: cols, alignment: .leading, spacing: 5) {
            ForEach(0..<m.n, id: \.self) { i in
                Circle()
                    .fill(i < m.ok ? DS.textPrimary : .clear)
                    .overlay(Circle().strokeBorder(DS.textPrimary.opacity(i < m.ok ? 0 : (known ? 0.26 : 0.14)), lineWidth: 1.5))
                    .frame(width: 12, height: 12)
                    .scaleEffect(i < m.ok && !shown && !reduceMotion ? 0 : 1)
                    .animation(reduceMotion || i >= m.ok ? nil : DS.Motion.dotPop.delay(DS.Motion.dotStagger * Double(base + i)), value: shown)
            }
        }
        .fixedSize()
    }
}

// MARK: 바깥 틀: 차트 글자는 xxLarge까지만 키운다
// 가장 큰 글자에서는 줄 머리·요일이 트랙 폭을 넘는다. 값은 차트 요약(화면 읽기)·머리·숫자 줄이 큰 글자로 읽힌다.
// 상한을 바깥에 두어 안쪽 @ScaledMetric(fs)도 상한을 본다.

struct DayBandChart: View {
    let data: ChartData.Day
    var body: some View { DayBandChartBody(data: data).dynamicTypeSize(...DynamicTypeSize.xxLarge) }
}

struct WeekBarsChart: View {
    typealias Item = WeekBarsChartBody.Item
    let items: [Item]
    let hot: Color
    var target: (value: Double, label: String)? = nil
    var height: CGFloat = 150
    let summary: String
    var body: some View {
        WeekBarsChartBody(items: items, hot: hot, target: target, height: height, summary: summary).dynamicTypeSize(...DynamicTypeSize.xxLarge)
    }
}

struct SleepHeatChart: View {
    let grid: ChartData.SleepGrid
    var body: some View { SleepHeatChartBody(grid: grid).dynamicTypeSize(...DynamicTypeSize.xxLarge) }
}

struct EpisodeBandChart: View {
    let band: ChartData.Episode
    let summary: String
    var body: some View { EpisodeBandChartBody(band: band, summary: summary).dynamicTypeSize(...DynamicTypeSize.xxLarge) }
}
