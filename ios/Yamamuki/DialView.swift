import SwiftUI
import UIKit
import YamamukiCore

/// 方位盤の画面。位置情報の許可、現在地、方位をつないで [DialCanvasView] に渡す。
struct DialView: View {
    let model: DialModel

    @Environment(\.scenePhase) private var scenePhase
    /// 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    @State private var selectedId: Int64?
    @State private var showSettings = false
    @State private var lastMagnification: CGFloat = 1

    var body: some View {
        ZStack {
            dialBeige.ignoresSafeArea()

            DialCanvasView(
                headingDeg: model.heading ?? 0,
                mountains: model.mountains,
                rangeKm: model.rangeKm,
                summit: model.summit,
                altitudeM: model.location?.mslAltitudeM,
                maxPeaks: model.settings.maxPeaks,
                textScale: model.settings.textScale,
                onMountainTap: { selectedId = $0.mountain.osmId }
            )

            if model.settings.networkConsentAsked && !model.hasLocationPermission {
                PermissionRequest(denied: model.authorization == .denied || model.authorization == .restricted) {
                    model.requestLocationPermission()
                }
            } else if model.hasLocationPermission {
                VStack {
                    StatusLine(
                        message: statusMessage,
                        // 手動取得モードでは左下の更新ボタンで取り直すので、ここには出さない。
                        actionLabel: model.offline && !model.loading && !model.settings.manualFetch ? "再取得" : nil,
                        onAction: model.retry
                    )
                    .padding(.top, 76)
                    Spacer()
                }
            }

            VStack {
                Spacer()
                HStack(alignment: .bottom) {
                    bottomButtons
                    Spacer()
                    Text("© OpenStreetMap contributors")
                        .font(.caption2)
                        .foregroundStyle(.black)
                }
                .padding(8)
            }
        }
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { value in
                    // 前回からの変化分だけを渡す(Android 版のピンチと同じ扱い)。
                    model.onZoom(Double(value.magnification / lastMagnification))
                    lastMagnification = value.magnification
                }
                .onEnded { _ in lastMagnification = 1 }
        )
        .onAppear { model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.start() } else { model.stop() }
        }
        // 屋外で山を見比べている間に画面が消えないようにする(設定で選んだときだけ)。
        .onChange(of: model.settings.keepScreenOn, initial: true) { _, on in
            UIApplication.shared.isIdleTimerDisabled = on
        }
        .alert("山データの取得", isPresented: .constant(!model.settings.networkConsentAsked)) {
            Button("はい") { model.answerNetworkConsent(allow: true) }
            Button("いいえ") { model.answerNetworkConsent(allow: false) }
        } message: {
            Text(
                "周辺の山の名前・位置・標高を OpenStreetMap（Overpass API）から取得します。" +
                    "問い合わせには現在地周辺の範囲が含まれます。" +
                    "通信量は 1 回あたり数十 KB 程度で、取得したデータは端末に保存して使い回します。\n\n" +
                    "自動で取得してよいですか？\n" +
                    "「いいえ」なら、画面左下の更新ボタンを押したときだけ通信します。設定はあとから変更できます。"
            )
        }
        .fetchErrorAlert(model)
        // シートを開いている間は下の画面からアラートを出せないので、シートの中身にも付ける。
        .sheet(isPresented: $showSettings) {
            SettingsView(model: model).fetchErrorAlert(model)
        }
        .sheet(item: selectedMountain) { nearby in
            MountainDetailView(nearby: nearby).fetchErrorAlert(model)
        }
    }

    /// 左下: 設定と、手動取得モードなら山データの取得。屋外で押しやすいよう大きめにする。
    private var bottomButtons: some View {
        HStack(spacing: 8) {
            RoundButton(label: "設定") {
                Image(systemName: "gearshape.fill")
            } action: {
                showSettings = true
            }
            if model.hasLocationPermission && model.settings.manualFetch {
                RoundButton(label: "山データを取得") {
                    if model.loading {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                } action: {
                    model.fetchManually()
                }
                .disabled(model.location == nil || model.loading)
            }
        }
    }

    /// 取り直しで一覧から消えたら選択も解く(シートが閉じる)。
    private var selectedMountain: Binding<NearbyMountain?> {
        Binding(
            get: {
                guard let id = selectedId else { return nil }
                return model.mountains.first { $0.mountain.osmId == id }
                    ?? model.summit.flatMap { $0.mountain.osmId == id ? $0 : nil }
            },
            set: { if $0 == nil { selectedId = nil } }
        )
    }

    private var statusMessage: String? {
        if model.location == nil { return "現在地を取得しています…" }
        if model.heading == nil { return "方位センサーの値を待っています…" }
        if model.loading { return "山データを取得中…" }
        if model.offline && model.incomplete { return "通信できず、この付近の山データがありません" }
        if model.offline { return "オフライン: 保存済みのデータで表示中" }
        if model.settings.manualFetch && model.incomplete { return "この付近の山データがありません。左下の更新ボタンで取得できます" }
        return nil
    }
}

/// 通信に失敗したら画面中央で知らせる。方位を待つ表示などに隠れて気づけないことがないようにする。
private struct FetchErrorAlert: ViewModifier {
    let model: DialModel

    func body(content: Content) -> some View {
        content.alert(
            "山データを取得できませんでした",
            isPresented: Binding(
                get: { model.fetchErrorMessage != nil },
                set: { if !$0 { model.dismissFetchError() } }
            )
        ) {
            Button("再取得") { model.retryAfterFetchError() }
            Button("閉じる", role: .cancel) { model.dismissFetchError() }
        } message: {
            Text(model.fetchErrorMessage ?? "")
        }
    }
}

private extension View {
    func fetchErrorAlert(_ model: DialModel) -> some View {
        modifier(FetchErrorAlert(model: model))
    }
}

private struct RoundButton<Content: View>: View {
    let label: String
    @ViewBuilder let content: () -> Content
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            content()
                .font(.system(size: 24))
                .frame(width: 52, height: 52)
                .background(Circle().fill(Color.white.opacity(0.7)))
        }
        .tint(.black)
        .accessibilityLabel(label)
    }
}

private struct StatusLine: View {
    let message: String?
    let actionLabel: String?
    let onAction: () -> Void

    var body: some View {
        if let message {
            HStack {
                Text(message).font(.footnote).foregroundStyle(.black)
                if let actionLabel {
                    Button(actionLabel, action: onAction).font(.footnote.bold())
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }
}

private struct PermissionRequest: View {
    /// 一度断られていると、iOS はアプリから許可の画面を出せないので、設定アプリに案内する。
    let denied: Bool
    let onRequest: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(
                denied
                    ? "周辺の山を表示するには、位置情報の許可が必要です。\n設定アプリで「位置情報」を「使用中のみ」にしてください。"
                    : "周辺の山を表示するには、位置情報の許可が必要です。"
            )
            .multilineTextAlignment(.center)
            .foregroundStyle(.black)
            Button(denied ? "設定を開く" : "許可する") {
                if denied {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                } else {
                    onRequest()
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }
}

/// タップした山の詳細。
private struct MountainDetailView: View {
    let nearby: NearbyMountain
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let m = nearby.mountain
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(m.name).font(.title2.bold())
                Spacer()
                Button("閉じる") { dismiss() }
            }
            DetailRow(label: "標高", value: m.elevationText)
            DetailRow(label: "緯度経度", value: m.coordinateText)
            DetailRow(label: "現在地からの距離", value: distanceText(nearby.distanceKm))
            Spacer()
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}

private struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }
}
