import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct AigyeotWidgets: WidgetBundle {
    init() { DesignSystem.registerHostAppFont() }
    var body: some Widget { CareLiveActivity() }
}

/// 잠금 화면·Dynamic Island의 진행 중 수유·잠(C6, 시안 S.lock .live-act): 점 + "수유 중"·"왼쪽" + 경과(가벼운 큰 숫자) + "끝내기".
/// 경과는 시스템 시계가 그린다(앱이 매초 깨지 않는다, R15). 잠금 화면은 어두운 글래스 판에 흰 글자(시안과 같음).
struct CareLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CareActivityAttributes.self) { context in
            VStack(spacing: 12) {
                HStack(spacing: 12) {
                    Circle().fill(DesignSystem.liveActivityDot).frame(width: 10, height: 10).padding(.leading, 6)   // 시안 .live-act .ri
                    VStack(alignment: .leading, spacing: 0) {
                        Text(title(context.attributes)).font(DesignSystem.namedFont(16, 700))
                        if let side = context.state.side { Text(side).font(DesignSystem.namedFont(13, 500)).opacity(0.8) }
                    }
                    .fixedSize()   // 제목·쪽은 늘 다 보이고, 경과가 남은 폭에서 줄어든다
                    Spacer(minLength: 8)
                    // 큰 글자에서도 1:02:34 전체가 들어가게 줄이고(한 줄), 글자 크기 상한을 둔다
                    timer(context).font(DesignSystem.namedFont(32, 300, relativeTo: .title).monospacedDigit())
                        .multilineTextAlignment(.trailing).lineLimit(1).minimumScaleFactor(0.5)
                        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                }
                endButton(context)
            }
            .foregroundStyle(.white)
            .padding(16)
            // 시안은 어두운 글래스(42%)지만 흰 잠금 화면 위에서 흰 글자가 4.5:1을 밑돈다. 거의 불투명한 어두운 판으로 둔다
            .activityBackgroundTint(DesignSystem.liveActivityPanel)
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    icon(context.attributes).resizable().scaledToFit().frame(width: 24, height: 24).foregroundStyle(tint(context.attributes))
                }
                DynamicIslandExpandedRegion(.center) {
                    line(context).font(DesignSystem.namedFont(16, 700)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) { endButton(context) }
            } compactLeading: {
                icon(context.attributes).resizable().scaledToFit().frame(width: 18, height: 18).foregroundStyle(tint(context.attributes))
            } compactTrailing: {
                // 결정(11-07): 압축 섬은 아이콘만. 좁은 섬(iPhone Care 약 30pt)에서 1시간 넘는 경과가 '1:00…'으로 잘렸다. 경과는 펼친 섬·잠금 화면에 시:분:초로 보인다
                EmptyView()
            } minimal: {
                icon(context.attributes).resizable().scaledToFit().frame(width: 18, height: 18).foregroundStyle(tint(context.attributes))
            }
        }
    }

    private func title(_ a: CareActivityAttributes) -> String { a.sleep ? CareActivityText.sleeping : CareActivityText.feeding }

    /// "수유 중 · 12:34 · 왼쪽" / "자는 중 · 0:40"
    private func line(_ c: ActivityViewContext<CareActivityAttributes>) -> Text {
        let head = Text(title(c.attributes) + " · ")
        let withTimer = head + timer(c)
        return c.state.side.map { withTimer + Text(" · " + $0) } ?? withTimer
    }

    private func timer(_ c: ActivityViewContext<CareActivityAttributes>) -> Text {
        Text(timerInterval: c.state.startAt...Date.distantFuture, countsDown: false)
    }

    /// 끝내기: 흰 18% 알약 + 흰 굵은 글자(어두운 판 위 대비 4.5:1 이상).
    private func endButton(_ c: ActivityViewContext<CareActivityAttributes>) -> some View {
        Button(intent: EndCareIntent(eventId: c.attributes.eventId)) {
            Text(CareActivityText.end).font(DesignSystem.namedFont(15, 700)).foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 44)   // 터치 영역 44pt(8장)
                .background(.white.opacity(0.18), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    /// 압축·최소 섬은 아이콘만 보여 이름을 단다(7.1 notify.title).
    private func icon(_ a: CareActivityAttributes) -> Image {
        Image(a.sleep ? "ic-moon" : "ic-bottle", label: Text(a.sleep ? CareActivityText.sleeping : CareActivityText.feeding))
    }
    private func tint(_ a: CareActivityAttributes) -> Color { a.sleep ? DesignSystem.cSleep : DesignSystem.cFeed }
}
