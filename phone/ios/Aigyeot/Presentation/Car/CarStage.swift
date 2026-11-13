import SwiftUI
import RealityKit
import Combine

private typealias DS = DesignSystem

// 폰 차 카드의 실시간 3D(시안 v6 ⑫, js/car3d.js·car-stage.js): 같은 모델(aigyeot_car.usdz), 같은 카메라 자리 표 PRESET, 같은 상태 빛.
// RealityKit ARView(.nonAR) 한 경로로 iOS 17~27을 그린다(스파이크 tools/measure/phone_ios_v6_3d.txt, R6 결정).
// 그리기는 필요할 때만: ARView는 멈춘 장면도 초당 60번 갱신하므로, 손가락으로 돌리거나 카메라가 옮겨 가는 동안만 창에 붙이고
// 멈추면 그때의 정지 그림을 깔고 창에서 뗀다(대기 중 0프레임).

/// 카메라 자리(차 좌표: 앞 +X, 위 +Y, 오른쪽 +Z). az는 앞에서 왼쪽(운전석)으로 도는 각. 시안 PRESET 그대로(폰은 phone·tile만 쓴다).
struct CarPreset: Equatable {
    let az: Float, el: Float, d: Float, r: Float, fov: Float
    let target: SIMD3<Float>

    static let hero = CarPreset(az: 38, el: 15, d: 9.4, r: 3.0, fov: 27, target: [0.1, 0.62, 0])
    static let phone = CarPreset(az: 34, el: 14, d: 10.6, r: 3.0, fov: 26, target: [0.05, 0.6, 0])
    static let tile = CarPreset(az: 34, el: 13, d: 8.3, r: 2.75, fov: 26, target: [0.05, 0.58, 0])

    /// 화면이 세로로 길면 차 폭(반폭 r)이 들어오게 물러난다(시안 fitD).
    func distance(aspect: Float) -> Float {
        let hf = atan(tan(fov * .pi / 360) * aspect)
        return max(d, r / tan(hf))
    }

    /// 구면 좌표 → 카메라 위치(시안 applyCam).
    static func position(az: Float, el: Float, d: Float, target: SIMD3<Float>) -> SIMD3<Float> {
        let a = az * .pi / 180, e = el * .pi / 180
        return SIMD3(cos(e) * cos(a), sin(e), -cos(e) * sin(a)) * d + target
    }

    /// `from`에서 `to`로 짧은 쪽으로 도는 각도(−180 이상 180 미만). 음수 각도에도 맞는 나머지로 계산한다
    /// (truncatingRemainder는 음수 부호를 남겨 594°에서 +126° 대신 −234°로 돌았다).
    static func shortestTurn(from: Float, to: Float) -> Float {
        let d = to - from + 180
        return d - 360 * (d / 360).rounded(.down) - 180
    }
}

/// 3D 준비 표시(U90, 시안 README v8c, 안드로이드 `stageMark`와 같은 규칙). 시각은 무대를 붙인 때부터 ms.
enum StageMark: Equatable {
    /// 아무것도 그리지 않는다(800ms 전, 첫 그림 뒤).
    case hidden
    /// 도는 고리('3D 차 준비 중').
    case ring
    /// 동작 줄이기의 정지 표시('3D 차 준비 중').
    case still
    /// 준비 실패: 정지 표시만 남고 읽지 않는다.
    case failed

    static let appearMs: Int64 = 800, minShowMs: Int64 = 400
    /// 고리는 한 바퀴 1초를 초당 15장으로 끊어 돈다(한 장 24°, 11-11 결정, 세 앱 같음). 매 장 부드럽게 돌리지 않는다.
    static let ringFps = 15

    /// 붙인 뒤 `t`ms에 고리가 가리키는 각(도, 12시 기준 시계 방향). 1000/15ms마다 24°씩 건너뛴다.
    static func ringAngle(atMs t: Int64) -> Double { Double((max(0, t) * Int64(ringFps) / 1000) % Int64(ringFps)) * 360 / Double(ringFps) }

    /// 한 바퀴의 장들(0, 24, …, 336°). 고리 애니메이션이 이 값을 그대로 끊어 쓴다.
    static var ringSteps: [Double] { (0..<ringFps).map { Double($0) * 360 / Double(ringFps) } }

    /// 800ms가 지나도 첫 그림이 없으면 보이고, 한 번 보였으면 800 + 400ms까지는 둔다. 첫 그림이 800ms 안에 오면 끝까지 보이지 않는다.
    /// 실패는 언제든(800 전이어도) 그때부터 정지 표시다. 기다리는 시간 상한은 두지 않는다.
    static func at(_ t: Int64, firstFrame: Int64?, failed: Int64?, reduced: Bool) -> StageMark {
        if let f = failed, t >= f { return .failed }
        guard t >= appearMs else { return .hidden }
        if let ff = firstFrame {
            if ff <= appearMs || t >= hideAt(firstFrame: ff) { return .hidden }
        }
        return reduced ? .still : .ring
    }

    /// 고리가 보였던 때(첫 그림이 800ms 뒤) 사라지기 시작하는 시각: 첫 그림과 800 + 400 중 늦은 쪽.
    static func hideAt(firstFrame ff: Int64) -> Int64 { max(ff, appearMs + minShowMs) }
}

/// 차 카드의 무대: 정지 그림(대기) ↔ 실시간 3D(손가락 회전·카메라 이동 중). 처음에는 '끌어서 돌려 보기' 대신 작은 회전 표시만(시안 .live3d::after).
/// 3D 첫 그림 전 준비 표시(U90)는 무대(CarStageView) 안에서 Core Animation으로 그린다(아래 `StageMarkLayer`).
struct CarStageCard: View {
    var preset: CarPreset = .tile
    /// 작은 회전 표시(시안 .car-tile .live3d::after). 첫 안내 무대(.ih3d)에는 없다.
    var hint = true
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var moved = false
    /// 3D 준비: 첫 그림 전·첫 그림 뒤·준비 실패(회전 표시는 차가 보인 뒤에만, 읽기 이름은 준비 중에만).
    @State private var phase: Phase = .loading
    private enum Phase { case loading, ready, failed }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            CarStageRepresentable(preset: preset, dark: scheme == .dark, reduceMotion: reduceMotion,
                                  onMoved: { moved = $0 }, onFirstFrame: { phase = .ready }, onFailed: { phase = .failed })
            if phase == .loading {   // 읽기 이름 '3D 차 준비 중'(7.1 design.stage.loading). 그림은 무대 안 StageMarkLayer, 실패면 읽지 않는다
                Color.clear.frame(width: 28, height: 28)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityElement()
                    .accessibilityLabel(CareText.stageLoading)
            }
            let ready = phase == .ready
            if hint && !moved && ready {   // 돌린 뒤에는 무대 안의 '처음 자리로' 단추가 이 자리를 쓴다
                IconImage(.rotate3d, size: 16).foregroundStyle(DS.textTertiary)
                    .frame(width: 26, height: 26)
                    .background(DS.surface.opacity(0.8), in: Circle())
                    .padding(.trailing, 12).padding(.bottom, 10)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("우리 차 3D 그림")   // 끌어 돌리기는 화면 읽기로 쓸 수 없어 읽지 않는다(B26). 처음 모습은 '처음 자리로'
    }
}

/// 준비 표시(U90, 세 앱 같은 모양): 고리 지름 28·굵기 2.5, 바탕 고리 textPrimary 10%, 호 accentInk ¼바퀴·끝 둥글게, 1초에 한 바퀴.
/// 동작 줄이기·실패면 차 선 아이콘 28, textTertiary 50%. 플랫폼 기본 스피너는 쓰지 않는다.
/// 나타남(800ms)·사라짐을 붙일 때 미리 Core Animation에 걸어 둔다: 시뮬레이터에서 RealityKit 준비가 주 스레드를 1초 넘게 막아
/// (ARView 만들기 약 1.2초, 재질 덮어쓰기 조각 최대 0.6초, TMP 실측) 주 스레드로 돌리는 고리는 그동안 멈췄다. 이 고리는 그리기 서버가 돌린다.
@MainActor
private final class StageMarkLayer: UIView {
    private let track = CAShapeLayer()
    private let arc = CAShapeLayer()
    private let still = UIImageView(image: UIImage(named: Ico.carFront.assetName)?.withRenderingMode(.alwaysTemplate))
    private var reduced = false
    private var spinGen = 0   // 늦게 도는 정리가 그 사이 다시 단 회전을 떼지 않게 하는 세대 번호

    override init(frame: CGRect) {
        super.init(frame: CGRect(x: 0, y: 0, width: 28, height: 28))
        let path = UIBezierPath(ovalIn: bounds.insetBy(dx: 1.25, dy: 1.25)).cgPath
        for l in [track, arc] {
            l.frame = bounds
            l.path = path
            l.fillColor = nil
            l.lineWidth = 2.5
            layer.addSublayer(l)
        }
        arc.strokeEnd = 0.25
        arc.lineCap = .round
        arc.transform = CATransform3DMakeRotation(-.pi / 2, 0, 0, 1)   // 12시에서 시작
        still.frame = bounds
        addSubview(still)
        layer.opacity = 0
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (v: StageMarkLayer, _) in v.applyColors() }
        isUserInteractionEnabled = false
        isAccessibilityElement = false   // 읽기 이름은 카드(CarStageCard)가 준비 중에만 단다
    }

    required init?(coder: NSCoder) { fatalError() }

    func applyColors() {
        track.strokeColor = UIColor(DS.textPrimary).withAlphaComponent(0.1).resolvedColor(with: traitCollection).cgColor
        arc.strokeColor = UIColor(DS.accentInk).resolvedColor(with: traitCollection).cgColor
        still.tintColor = UIColor(DS.textTertiary).withAlphaComponent(0.5)
    }

    /// 무대를 붙일 때 한 번: `at`(그리기 서버 시각)에 220ms로 나타나게 걸어 둔다. 동작 줄이기면 정지 표시가 바로 나타난다.
    func arm(at: CFTimeInterval, reduced: Bool) {
        self.reduced = reduced
        stopSpin()
        layer.removeAnimation(forKey: "fadeOut")
        applyColors()
        arc.isHidden = reduced
        track.isHidden = reduced
        still.isHidden = !reduced
        if !reduced {
            // 초당 15장으로 끊어 돈다(한 장 24°, 보간하지 않음). 그리기 서버가 돌려 주 스레드가 막혀도 멈추지 않는다
            let spin = CAKeyframeAnimation(keyPath: "transform.rotation.z")
            spin.values = StageMark.ringSteps.map { -Double.pi / 2 + $0 * .pi / 180 }
            spin.keyTimes = (0...StageMark.ringFps).map { NSNumber(value: Double($0) / Double(StageMark.ringFps)) }
            spin.calculationMode = .discrete
            spin.duration = 1
            spin.repeatCount = .infinity
            spin.isRemovedOnCompletion = false
            arc.add(spin, forKey: "spin")
        }
        fade(to: 1, at: at, duration: reduced ? 0 : 0.22)
    }

    /// 첫 그림 뒤: `at`에 220ms로 사라진다(동작 줄이기면 바로). 보인 적이 없으면 바로 뗀다.
    /// 나타남('fade')은 그대로 두고 다른 키로 건다: 같은 키로 바꿔 걸면 나타나던 중간 투명도에서 멈춘 채 `at`까지 머물렀다
    /// (첫 그림 900ms: 800ms에 시작한 나타남이 반쯤에서 끊기고 1,200ms까지 그 투명도). `at`(hideAt)은 1,200ms 이상이라 그때 나타남은 끝나 있다.
    func dismiss(at: CFTimeInterval?) {
        guard let at else { layer.removeAllAnimations(); layer.opacity = 0; stopSpin(); return }
        let duration: CFTimeInterval = reduced ? 0 : 0.22
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = layer.opacity
        a.toValue = 0
        a.beginTime = at
        a.duration = max(duration, 0.001)
        a.fillMode = .forwards   // `at` 전에는 걸리지 않아 나타남이 끝까지 돈다. 끝난 뒤에는 0을 붙든다
        a.isRemovedOnCompletion = false
        layer.add(a, forKey: "fadeOut")
        // 투명해진 뒤에도 회전이 카드 수명 내내 남지 않게, 사라짐이 끝나면 뗀다(그사이 다시 arm 했으면 손대지 않는다)
        let gen = spinGen
        let wait = max(0, at + duration - CACurrentMediaTime()) + 0.05
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self, self.spinGen == gen else { return }
            self.stopSpin()
            self.layer.opacity = 0
            self.layer.removeAnimation(forKey: "fadeOut")
        }
    }

    private func stopSpin() {
        spinGen += 1
        arc.removeAllAnimations()
    }

    /// 준비 실패: 고리를 감추고 정지 표시만 남긴다. 읽지 않는다.
    func failed() {
        stopSpin()
        arc.isHidden = true
        track.isHidden = true
        still.isHidden = false
        layer.removeAllAnimations()
        layer.opacity = 1
    }

    private func fade(to v: Float, at: CFTimeInterval, duration: CFTimeInterval) {
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = layer.presentation()?.opacity ?? layer.opacity
        a.toValue = v
        a.beginTime = at
        a.duration = max(duration, 0.001)
        a.fillMode = .backwards
        layer.opacity = v
        layer.add(a, forKey: "fade")
    }
}

private struct CarStageRepresentable: UIViewRepresentable {
    let preset: CarPreset
    let dark: Bool
    let reduceMotion: Bool
    let onMoved: (Bool) -> Void
    let onFirstFrame: () -> Void
    let onFailed: () -> Void

    func makeUIView(context: Context) -> CarStageView {
        let v = CarStageView(preset: preset)
        v.dark = dark
        v.reduceMotion = reduceMotion
        v.onMoved = onMoved
        v.onFirstFrame = onFirstFrame
        v.onFailed = onFailed
        return v
    }

    func updateUIView(_ v: CarStageView, context: Context) {
        v.reduceMotion = reduceMotion
        if v.dark != dark { v.dark = dark }
    }

    static func dismantleUIView(_ v: CarStageView, coordinator: ()) { v.teardown() }
}

/// 정지 그림과 ARView를 바꿔 끼우는 틀. 카메라 상태(방위·높이)는 여기 하나에 둔다.
@MainActor
final class CarStageView: UIView {
    var dark = false { didSet { if oldValue != dark { applyTheme(); refreshStill() } } }
    var reduceMotion = false
    /// 돌렸는지(카드의 회전 표시를 감춘다).
    var onMoved: ((Bool) -> Void)?
    /// ARView를 붙인 뒤 첫 `SceneEvents.Update`(U90 첫 그림, 정지 그림 길·돌리기 길 둘 다). 한 번만 부른다.
    var onFirstFrame: (() -> Void)?
    /// 모델을 읽지 못했다(U90: 정지 표시만, 읽지 않음). 다음 카드에서 다시 해 본다.
    var onFailed: (() -> Void)?
    private var firstFrameSub: Cancellable?
    private var sawFirstFrame = false
    /// 준비 표시(U90)와 그 기준 시각(무대를 붙인 때, 그리기 서버 시각). 차(정지 그림·ARView)는 `content` 안에 있어 고리가 사라질 때 함께 들어온다.
    private let mark = StageMarkLayer()
    private let content = UIView()
    private var attachedAt: CFTimeInterval?

    private let preset: CarPreset
    private let still = UIImageView()
    /// '처음 자리로'(돌린 뒤에만 보인다, 시안 car-stage 되돌리기 단추·안드로이드와 같다). 44pt.
    private let reset = UIButton(type: .system)
    private var flight: CADisplayLink?
    private var flightStart: CFTimeInterval = 0
    private var flightFrom = SIMD2<Float>(0, 0)
    private var arView: ARView?
    private var camera = PerspectiveCamera()
    private let keyLight = DirectionalLight()
    private var az: Float, el: Float
    /// 아직 돌지 않은 끈 양(점). 프레임마다 9%씩 돌린다.
    private var pending = SIMD2<Float>(0, 0)
    private var dragging = false
    private var settle: Task<Void, Never>?
    private var creating: Task<ARView?, Never>?
    /// 사용자가 돌리는 중(손가락·감쇠). 이때만 ARView가 창에 붙어 있다.
    private var live = false
    private var decay: CADisplayLink?
    private var lastSize: CGSize = .zero
    private static var model: Entity?
    private static var loading: Task<Entity?, Never>?

    init(preset: CarPreset) {
        self.preset = preset
        az = preset.az
        el = preset.el
        super.init(frame: .zero)
        still.contentMode = .scaleAspectFill
        still.isAccessibilityElement = false
        content.isUserInteractionEnabled = false
        addSubview(content)
        content.addSubview(still)
        addSubview(mark)
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
        reset.setImage(UIImage(named: Ico.locateFixed.assetName)?.withRenderingMode(.alwaysTemplate), for: .normal)
        reset.tintColor = UIColor(DS.textSecondary)
        reset.backgroundColor = UIColor(DS.surface).withAlphaComponent(0.85)
        reset.layer.cornerRadius = 22
        reset.accessibilityLabel = "처음 자리로"   // design.stage(화면 글자 없음)
        reset.isHidden = true
        reset.addTarget(self, action: #selector(backToStart), for: .touchUpInside)
        addSubview(reset)
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError() }

    /// 카드는 스크롤 화면 안에 있다: 가로로 끌 때만 돌리고, 세로로 끌면 화면이 굴러가게 둔다(세로 밀기로 차가 위에서 내려다보이게 뒤집히지 않게).
    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let p = g as? UIPanGestureRecognizer else { return super.gestureRecognizerShouldBegin(g) }
        let v = p.velocity(in: self)
        return abs(v.x) > abs(v.y)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        content.frame = bounds
        still.frame = bounds
        arView?.frame = bounds
        mark.center = CGPoint(x: bounds.midX, y: bounds.midY)   // 무대 칸 가운데
        reset.frame = CGRect(x: bounds.width - 56, y: bounds.height - 54, width: 44, height: 44)
        guard bounds.width > 0, bounds.size != lastSize else { return }
        lastSize = bounds.size
        if attachedAt == nil {   // 무대를 붙였다: 800ms 뒤 나타날 준비 표시를 걸어 둔다(U90)
            let now = CACurrentMediaTime()
            attachedAt = now
            mark.arm(at: now + Double(StageMark.appearMs) / 1000, reduced: reduceMotion)
        }
        // 크기가 정해진 뒤 한 번 그려 정지 그림을 만든다(크기 0에서 거리를 재면 무한대가 된다).
        refreshStill()
    }

    func teardown() {
        flight?.invalidate()
        settle?.cancel()
        creating?.cancel()
        decay?.invalidate()
        firstFrameSub?.cancel()
        firstFrameSub = nil
        arView?.removeFromSuperview()
        arView = nil
    }

    // MARK: 모델·장면

    /// USDZ는 앱에서 한 번만 읽는다(약 1초·24MB, 스파이크). 카드마다 복제해 쓴다.
    private static func loadModel() async -> Entity? {
        if let m = model { return m }
        if let l = loading { return await l.value }
        let t = Task { @MainActor () -> Entity? in
            guard let url = Bundle.main.url(forResource: "aigyeot_car", withExtension: "usdz"),
                  let e = await loadAsync(url) else { loading = nil; return nil }   // 못 읽으면 다음 카드에서 다시 해 본다(무대는 빈 채로 남고 화면은 멈추지 않는다)
            await restyle(e)
            model = e
            return e
        }
        loading = t
        return await t.value
    }

    /// 동기 `Entity.load`는 주 스레드를 약 1초 막는다(스파이크 0.86~1.41초). 시스템이 뒤에서 읽고 주 스레드에서 돌려준다(iOS 13+).
    private static func loadAsync(_ url: URL) async -> Entity? {
        await withCheckedContinuation { (c: CheckedContinuation<Entity?, Never>) in
            var done = false
            var sub: AnyCancellable?
            sub = Entity.loadAsync(contentsOf: url).sink(receiveCompletion: { r in
                if case .failure = r, !done { done = true; c.resume(returning: nil) }
                sub?.cancel()
            }, receiveValue: { e in
                if !done { done = true; c.resume(returning: e) }
            })
            _ = sub
        }
    }

    /// 재질 이름으로 덮어쓴다(시안 v7 car3d.js materials(): 도장 + 클리어코트, 크롬, 유리, 선루프 가림막, 램프는 발광). 이름은 iOS 18+ API라
    /// 17은 USDZ 재질 그대로(앰비언트만 엔티티 이름으로). 모든 재질은 양면으로 그린다(시안 v7 DoubleSide: 차체가 얇은 판이라 안쪽 면이 비친다).
    /// 엔티티를 하나씩 고치되 8ms마다 주 스레드를 놓는다: 한 번에 하면 주 스레드를 1초 넘게 막아(시뮬레이터 실측 1.28초)
    /// 준비 표시(U90) 고리와 화면 안무가 멈췄다. RealityKit 엔티티는 주 스레드에서만 고칠 수 있어 나눠서 한다.
    private static func restyle(_ root: Entity) async {
        var stack = [root]
        var slice = CACurrentMediaTime()
        while let e = stack.popLast() {
            if var m = e.components[ModelComponent.self] {
                m.materials = m.materials.map { mat in
                    var out = mat
                    if #available(iOS 18, *) { out = named(mat) }
                    if var p = out as? PhysicallyBasedMaterial { p.faceCulling = .none; return p }
                    if #available(iOS 18, *), var u = out as? UnlitMaterial { u.faceCulling = .none; return u }   // Unlit은 18+ API
                    return out
                }
                e.components.set(m)
            }
            stack.append(contentsOf: e.children)
            if CACurrentMediaTime() - slice > 0.008 {
                try? await Task.sleep(for: .milliseconds(1))   // 한 장을 그릴 틈
                slice = CACurrentMediaTime()
            }
        }
        if #unavailable(iOS 18) { glowAmbientByEntityName(root) }
    }

    /// 시안 v7 값. 옮기지 못한 것: 크롬 환경광 1.5배·유리 환경광 1.0배(재질마다 환경광 배율이 없다), 유리 투과(USDZ의 짙은 불투명 유리 그대로).
    @available(iOS 18, *)
    private static func named(_ mat: any RealityKit.Material) -> any RealityKit.Material {
        switch mat.name {
        case "Paint"?:
            var p = PhysicallyBasedMaterial()
            p.baseColor = .init(tint: UIColor(hex: DS.CarPaint.body))
            p.roughness = 0.42
            p.metallic = 0.26
            p.clearcoat = .init(floatLiteral: 1)
            p.clearcoatRoughness = .init(floatLiteral: 0.03)
            return p
        case "Chrome"?:
            var p = PhysicallyBasedMaterial()
            p.baseColor = .init(tint: UIColor(hex: DS.CarPaint.chrome))
            p.roughness = 0.08
            p.metallic = 1.0
            return p
        case "Glass_Front"?, "Glass_Rear"?, "Glass_Side"?, "Sunroof_Glass"?:
            // 굽은 유리 모서리의 직사광 얼룩을 줄인다: 매끈하게(0.03), 정반사 세기 0.25(시안 specularIntensity 0.25, 색은 USDZ 그대로)
            guard var p = mat as? PhysicallyBasedMaterial else { return mat }
            p.roughness = 0.03
            p.specular = PhysicallyBasedMaterial.Specular(floatLiteral: 0.25)
            return p
        case "Roofglass_Shade"?:   // 검은 가림막: 지붕 유리가 앞·뒤 유리와 같은 검정으로 보인다
            var p = PhysicallyBasedMaterial()
            p.baseColor = .init(tint: UIColor(hex: DS.CarPaint.roofShade))
            p.roughness = 0.92
            return p
        case "Ambient"?:   // 상태 빛 '듣는 중': 실내 앰비언트 선이 곁의 응답 파랑(tokens glow)으로 빛난다(시안 applyState listen 2.2)
            var p = PhysicallyBasedMaterial()
            p.baseColor = .init(tint: UIColor(hex: DS.CarPaint.ambientBase))
            p.emissiveColor = .init(color: UIColor(hex: DS.CarPaint.ambientGlow))
            p.emissiveIntensity = 2.2
            return p
        case "Head_L_All"?: return UnlitMaterial(color: UIColor(hex: DS.CarPaint.headLamp))
        case "Rear_L_Pos"?: return UnlitMaterial(color: UIColor(hex: DS.CarPaint.tailLamp))
        default: return mat
        }
    }

    /// iOS 17: Material.name이 없어 재질 이름으로 덮어쓰지 못한다. 앰비언트 선은 엔티티 이름(Ambient_*)으로 찾아 상태 빛만 켠다.
    /// 도장·램프는 USDZ 재질 그대로(옅은 회색 도장·클리어코트, 미판정: 17 런타임이 맥에 없어 그려 보지 못함).
    private static func glowAmbientByEntityName(_ e: Entity) {
        if e.name.hasPrefix("Ambient"), var m = e.components[ModelComponent.self] {
            var p = PhysicallyBasedMaterial()
            p.baseColor = .init(tint: UIColor(hex: DS.CarPaint.ambientBase))
            p.emissiveColor = .init(color: UIColor(hex: DS.CarPaint.ambientGlow))
            p.emissiveIntensity = 2.2
            p.faceCulling = .none
            m.materials = m.materials.map { _ in p }
            e.components.set(m)
        }
        e.children.forEach(glowAmbientByEntityName)
    }

    /// ARView는 이 카드에 하나뿐이다: 모델을 기다리는 동안 다시 불려도(크기 바뀜·손가락) 같은 생성 작업을 기다린다.
    private func makeARView() async -> ARView? {
        if let a = arView { return a }
        if let c = creating { return await c.value }
        let t = Task { @MainActor [weak self] () -> ARView? in await self?.buildARView() }
        creating = t
        let v = await t.value
        creating = nil
        return v
    }

    private func buildARView() async -> ARView? {
        // ARView를 먼저 만든다: 만들기가 주 스레드를 0.5~1.2초 막는데(시뮬레이터 TMP 실측) 무대를 붙인 직후에 끝내고,
        // 그 뒤 모델을 읽는 동안은 주 스레드가 비어 화면 안무가 멈추지 않게(모델을 다 읽은 뒤 만들면 그만큼 화면이 멈췄다)
        guard window != nil || superview != nil else { return nil }
        let v = ARView(frame: bounds, cameraMode: .nonAR, automaticallyConfigureSession: false)
        guard let model = await Self.loadModel() else { mark.failed(); onFailed?(); return nil }   // 못 읽음: 무대에 정지 표시만(U90, 읽지 않음)
        // 첫 그림: 이 ARView가 창에 붙어 처음 갱신될 때(실측 phone_ios_v7_3d의 '첫 그림'과 같은 사건). 한 번 부르고 구독을 끊는다
        if !sawFirstFrame {
            firstFrameSub = v.scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
                guard let self, !self.sawFirstFrame, v.window != nil else { return }
                self.sawFirstFrame = true
                self.firstFrameSub?.cancel()
                self.firstFrameSub = nil
                self.firstFrame()
                self.onFirstFrame?()
            }
        }
        v.renderOptions = [.disableMotionBlur, .disableDepthOfField, .disableHDR]
        let anchor = AnchorEntity(world: .zero)
        anchor.addChild(model.clone(recursive: true))
        // 손으로 그린 바닥 그림자 대신 부드러운 원판(시안 shadow): 차 아래 옅은 검정
        var shade = UnlitMaterial(color: .black)
        shade.blending = .transparent(opacity: .init(floatLiteral: 0.12))
        let shadow = ModelEntity(mesh: .generatePlane(width: 5.6, depth: 2.2, cornerRadius: 1.1), materials: [shade])
        shadow.position = [-0.08, 0.003, 0]
        anchor.addChild(shadow)
        keyLight.look(at: .zero, from: [3, 6, -2], relativeTo: nil)
        anchor.addChild(keyLight)
        camera = PerspectiveCamera()
        anchor.addChild(camera)
        v.scene.addAnchor(anchor)
        arView = v
        applyTheme()
        pose()
        return v
    }

    /// 시안 v7 car3d.js studio(): 다크는 방·바닥·조명 판(환경)만 0.55배다. RealityKit 기본 환경광 세기(2의 지수)에만 주고,
    /// 직사광(주광)은 테마와 무관하게 그대로 둔다(시안 DirectionalLight 1.4, 안드로이드와 같다).
    /// 옮기지 못한 것: 천장·벽 띠 조명의 배치(띠 모양 반사). 시안은 빛나는 판으로 환경을 만들고, 여기는 기본 환경광 + 주광 하나다.
    private func applyTheme() {
        arView?.environment.background = .color(.clear)
        arView?.backgroundColor = .clear
        arView?.environment.lighting.intensityExponent = dark ? log2(0.55) : 0
        keyLight.light.intensity = 2600
    }

    /// 첫 그림(U90): 고리가 보이기 전(800ms 안)이면 바로 뗀다. 보였으면 800 + 400ms와 첫 그림 중 늦은 때에 220ms로 사라지고
    /// 3D는 그때 360ms(`enter`)로 들어온다. 시각은 `StageMark` 규칙 한 곳이다.
    private func firstFrame() {
        guard let start = attachedAt else { return }
        let ff = Int64((CACurrentMediaTime() - start) * 1000)
        guard ff > StageMark.appearMs else { mark.dismiss(at: nil); return }
        let hide = start + Double(StageMark.hideAt(firstFrame: ff)) / 1000
        mark.dismiss(at: hide)
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = 0
        a.toValue = 1
        a.beginTime = hide
        a.duration = reduceMotion ? 0.001 : 0.36
        a.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.8, 0.2, 1)
        a.fillMode = .backwards
        content.layer.add(a, forKey: "enter")
    }

    private func pose() {
        let aspect = bounds.height > 0 ? Float(bounds.width / bounds.height) : 1.6
        camera.camera.fieldOfViewInDegrees = preset.fov
        let p = CarPreset.position(az: az, el: el, d: preset.distance(aspect: aspect), target: preset.target)
        camera.look(at: preset.target, from: p, relativeTo: nil)
    }

    // MARK: 정지 그림 ↔ 실시간

    /// 지금 자리를 한 장 그려 정지 그림으로 깔고 ARView를 뗀다(대기 중 0프레임).
    private func refreshStill() {
        guard bounds.width > 0 else { return }
        settle?.cancel()
        settle = Task { @MainActor [weak self] in
            guard let self, !self.live, let v = await self.makeARView(), !Task.isCancelled, !self.live else { return }   // 기다리는 사이 취소됐거나 돌리기 시작했으면 붙이지 않는다
            if v.superview == nil { self.content.insertSubview(v, belowSubview: self.still) }
            v.frame = self.bounds
            self.pose()
            try? await Task.sleep(for: .milliseconds(250))   // 첫 프레임이 그려질 틈
            guard !Task.isCancelled else { return }
            await self.park(v)
        }
    }

    private func park(_ v: ARView) async {
        let img: UIImage? = await withCheckedContinuation { c in v.snapshot(saveToHDR: false) { c.resume(returning: $0) } }
        guard !Task.isCancelled else { return }
        if let img { still.image = img }
        still.isHidden = false
        v.removeFromSuperview()
    }

    private func goLive() async {
        live = true
        settle?.cancel()
        guard let v = await makeARView(), live else { return }   // 모델을 기다리는 사이 손을 놓았으면 붙이지 않는다(정지 그림 그대로)
        if v.superview == nil { content.insertSubview(v, aboveSubview: still); v.frame = bounds }
        still.isHidden = true
    }

    // MARK: 손가락 회전(정차만) — 시안 OrbitControls(enableDamping, dampingFactor 0.09)와 같은 방식
    // 끈 양을 쌓아 두고 프레임마다 9%씩 돌린다. 놓으면 남은 양만 마저 돈다(놓는 속도로 더 돌지 않는다, 총 회전 = 끈 양).
    // 동작 줄이기면 쌓지 않고 바로 돌린다.

    @objc private func pan(_ g: UIPanGestureRecognizer) {
        switch g.state {
        case .began:
            dragging = true
            flight?.invalidate()   // '처음 자리로' 복귀 중에 끌면 복귀를 멈춘다(끝나며 돌린 각도를 덮고 정지 그림을 예약하지 않게, 안드로이드와 같다)
            flight = nil
            Task { await goLive() }
            if !reduceMotion { startDecay() }
        case .changed:
            let t = g.translation(in: self)
            g.setTranslation(.zero, in: self)
            if reduceMotion { rotate(dx: Float(t.x), dy: Float(t.y)) } else { pending += [Float(t.x), Float(t.y)] }
        case .ended, .cancelled:
            dragging = false
            if reduceMotion || decay == nil { scheduleSettle() }
        default: break
        }
    }

    private func startDecay() {
        guard decay == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(step))
        link.add(to: .main, forMode: .common)
        decay = link
    }

    /// 시안 OrbitControls와 같은 양: 카드 높이만큼 끌면 0.55바퀴(rotateSpeed 0.55), 높이 6~86°(minPolar·maxPolar).
    private func rotate(dx: Float, dy: Float) {
        let perPoint = 360 * 0.55 / Float(max(1, bounds.height))
        az = (az - dx * perPoint).truncatingRemainder(dividingBy: 360)   // 한 바퀴 안으로(안드로이드 % 360f)
        el = min(86, max(6, el + dy * perPoint))
        pose()
        moved(true)
    }

    /// 돌린 뒤에는 회전 표시 대신 '처음 자리로'를 보인다.
    private func moved(_ m: Bool) {
        reset.isHidden = !m
        onMoved?(m)
    }

    /// 처음 자리로: 카메라가 motion.camera(1.1초) 곡선으로 돌아간다. 동작 줄이기면 바로.
    @objc private func backToStart() {
        dragging = false
        pending = .zero
        decay?.invalidate()
        decay = nil
        flight?.invalidate()
        if reduceMotion {
            az = preset.az; el = preset.el
            moved(false)
            refreshStill()
            return
        }
        flightFrom = [az, el]
        flightStart = CACurrentMediaTime()
        Task { await goLive() }
        let link = CADisplayLink(target: self, selector: #selector(fly))
        link.add(to: .main, forMode: .common)
        flight = link
    }

    @objc private func fly() {
        let k = min(1, (CACurrentMediaTime() - flightStart) / DS.Motion.seconds("camera"))
        let e = Float(DS.Motion.curve("camera").value(at: k))   // motion.camera(1.1초, cubic-bezier .65,0,.35,1)
        let daz = CarPreset.shortestTurn(from: flightFrom.x, to: preset.az)
        az = flightFrom.x + daz * e
        el = flightFrom.y + (preset.el - flightFrom.y) * e
        pose()
        if k >= 1 {
            flight?.invalidate()
            flight = nil
            az = preset.az; el = preset.el
            pose()
            moved(false)
            scheduleSettle()
        }
    }

    @objc private func step() {
        let d = pending * 0.09
        pending -= d
        rotate(dx: d.x, dy: d.y)
        if !dragging, abs(pending.x) + abs(pending.y) < 0.05 {
            pending = .zero
            decay?.invalidate()
            decay = nil
            scheduleSettle()
        }
    }

    /// 손을 놓고 감쇠가 끝났다: 정지 그림으로 바꾸고 ARView를 뗀다. 모델이 아직 읽히는 중이면 그 작업을 기다린 뒤 뗀다(붙은 채 남지 않게).
    private func scheduleSettle() {
        live = false
        settle?.cancel()
        settle = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard let self, !Task.isCancelled, let v = await self.makeARView(), !Task.isCancelled, !self.live else { return }
            if v.superview == nil {   // 붙인 적이 없다(손을 먼저 놓음): 그리는 것이 없다. 첫 그림이 아직 없으면 지금 만든다
                self.still.isHidden = false
                if self.still.image == nil { self.refreshStill() }
                return
            }
            await self.park(v)
        }
    }
}
