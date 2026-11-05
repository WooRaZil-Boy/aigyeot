import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct AigyeotWidgets: WidgetBundle {
    var body: some Widget { CareLiveActivity() }
}

/// 잠금 화면·Dynamic Island의 진행 중 수유·잠(C6, 7장 "수유 중 · 12:34 · 왼쪽" / "자는 중 · 0:40" + "끝내기").
/// 경과는 시스템 시계가 그린다(앱이 매초 깨지 않는다, R15).
struct CareLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CareActivityAttributes.self) { context in
            HStack(spacing: 12) {
                Image(systemName: icon(context.attributes)).font(.title2).foregroundStyle(tint(context.attributes))
                line(context).font(.headline).monospacedDigit()
                Spacer(minLength: 0)
                endButton(context)
            }
            .padding(16)
            .activityBackgroundTint(Color(.systemBackground).opacity(0.8))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: icon(context.attributes)).font(.title2).foregroundStyle(tint(context.attributes))
                }
                DynamicIslandExpandedRegion(.center) {
                    line(context).font(.headline).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) { endButton(context) }
            } compactLeading: {
                Image(systemName: icon(context.attributes)).foregroundStyle(tint(context.attributes))
            } compactTrailing: {
                timer(context).monospacedDigit().frame(maxWidth: 52)
            } minimal: {
                Image(systemName: icon(context.attributes)).foregroundStyle(tint(context.attributes))
            }
        }
    }

    /// "수유 중 · 12:34 · 왼쪽" / "자는 중 · 0:40"
    private func line(_ c: ActivityViewContext<CareActivityAttributes>) -> Text {
        let head = Text((c.attributes.sleep ? CareActivityText.sleeping : CareActivityText.feeding) + " · ")
        let withTimer = head + timer(c)
        return c.state.side.map { withTimer + Text(" · " + $0) } ?? withTimer
    }

    private func timer(_ c: ActivityViewContext<CareActivityAttributes>) -> Text {
        Text(timerInterval: c.state.startAt...Date.distantFuture, countsDown: false)
    }

    private func endButton(_ c: ActivityViewContext<CareActivityAttributes>) -> some View {
        Button(intent: EndCareIntent(eventId: c.attributes.eventId)) { Text(CareActivityText.end).bold() }
            .buttonStyle(.borderedProminent)
            .tint(tint(c.attributes))
    }

    private func icon(_ a: CareActivityAttributes) -> String { a.sleep ? "moon.fill" : "drop.fill" }
    private func tint(_ a: CareActivityAttributes) -> Color { a.sleep ? .indigo : .orange }   // 앱 DesignSystem의 sleep·feed와 같은 값
}
