import Combine
import SwiftUI
import UIKit
import YamamukiCore

/// 方位盤の画面。位置情報の許可、現在地、方位をつないで [DialCanvasView] に渡す。
struct DialView: View {
    @ObservedObject var model: DialModel

    @Environment(\.scenePhase) private var scenePhase
    /// 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    @State private var selectedId: Int64?
    @State private var showSettings = false
    @State private var showDownload = false
    @State private var showObserver = false
    /// 重なる山をまとめた代表の山をタップしたときの一覧。ID で持ち、表示中の一覧から引く。
    @State private var groupIds: [Int64]?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                dialBeige.ignoresSafeArea()

                DialCanvasView(
                    headingDeg: model.displayHeading,
                    mountains: model.mountains,
                    rangeKm: model.rangeKm,
                    summit: model.summit,
                    altitudeM: model.gpsLocation?.mslAltitudeM,
                    maxPeaks: model.settings.maxPeaks,
                    textScale: model.settings.textScale,
                    observerLocation: model.gpsLocation,
                    viewportLocation: model.location,
                    compassHeading: model.heading ?? model.displayHeading,
                    headingUp: !model.exploring,
                    tapeHidden: manualChrome ? 1 : 0,
                    // 視野の扇は、現在地に戻り終えてから出す。
                    viewFanAlpha: model.exploring ? 0 : 1,
                    bottomBleed: geometry.safeAreaInsets.bottom,
                    onPan: { model.onPan(dx: $0, dy: $1, chartHeight: $2) },
                    onTransform: { model.onTransform(zoom: $0, rotation: $1, previous: $2, midpoint: $3, chartHeight: $4) },
                    onMountainTap: { selectedId = $0.mountain.osmId },
                    onGroupTap: { groupIds = $0.map(\.mountain.osmId) },
                    // 現在地を取れる前は出す値がないので開かない。
                    onObserverTap: { if model.gpsLocation != nil { showObserver = true } }
                )
                .animation(modeAnimation, value: manualChrome)
                .animation(modeAnimation, value: model.exploring)

                if model.settings.peakDataAsked && !model.hasLocationPermission {
                    PermissionRequest(denied: model.authorization == .denied || model.authorization == .restricted) {
                        model.requestLocationPermission()
                    }
                } else if model.hasLocationPermission {
                    VStack {
                        // 方位センサーの精度が低いと、方位が数十度ずれたまま別の山の名前を出してしまうので、上部で知らせる。
                        StatusLine(
                            // 方位が無効なあいだは方位の値が届かないので、値の有無にかかわらず出す。
                            message: model.headingAccuracyLow ? "コンパス補正中。8の字に動かしてください" : nil,
                            // 距離の円が文字の後ろを通っても読めるよう、方位の札と同じ淡い白の札にする。
                            labeled: true
                        )
                        StatusLine(message: statusMessage)
                        Spacer()
                    }
                    .padding(.top, CGFloat(DialGeometry.chartTop))
                    .padding(.horizontal, 16)
                }

                // 手動位置モードでは、方位目盛りの代わりに左上の向きの表示と右上のコンパスを左右から出す。
                VStack {
                    HStack {
                        if manualChrome {
                            headingLabel
                                .transition(.move(edge: .leading).combined(with: .opacity))
                        }
                        Spacer()
                        if manualChrome {
                            Button { model.faceNorth(canvasWidth: Double(geometry.size.width), canvasHeight: Double(geometry.size.height)) } label: {
                                CompassIndicator(heading: model.lockedHeading ?? model.heading)
                            }
                            .buttonStyle(.plain)
                            .disabled(model.location == nil || Double(geometry.size.height) <= DialGeometry.chartInset)
                            .accessibilityLabel("北を上にする")
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                    Spacer()
                }
                .padding(.top, 8)
                .padding(.horizontal, 8)
                .animation(modeAnimation, value: manualChrome)

                VStack {
                    Spacer()
                    HStack(alignment: .bottom) {
                        // 右下のモード切替ボタンと同じ高さにそろえるため、下に出典の文字 1 行分の高さをあける。
                        VStack(alignment: .leading, spacing: 12) {
                            bottomButtons
                            Text("©").font(.caption2).hidden()
                        }
                        .padding(.leading, 8)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 12) {
                            Button {
                                if model.exploring {
                                    model.resetCenter()
                                } else {
                                    model.enterManual()
                                }
                            } label: {
                                Image(systemName: model.exploring ? "scope" : "location.north.fill")
                                    .font(.system(size: 26, weight: .medium))
                                    .foregroundStyle(model.gpsLocation == nil ? Color.gray.opacity(0.4) :
                                        (model.exploring ? Color.gray : Color(red: 0.102, green: 0.451, blue: 0.910)))
                                    .frame(width: 56, height: 56)
                                    .background(Circle().fill(Color.white))
                                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
                            }
                            .buttonStyle(.plain)
                            .disabled(!model.hasLocationPermission || model.gpsLocation == nil || Double(geometry.size.height) <= DialGeometry.chartInset)
                            .accessibilityLabel(model.exploring ? "現在地に戻り、進行方向を上にする" : "今の向きのまま手動位置モードにする")
                            .accessibilityValue(model.exploring ? "手動位置モード" : "ヘディングアップモード")
                            .padding(.trailing, 8)

                            Text("© OpenStreetMap contributors")
                                .font(.caption2)
                                .foregroundStyle(.black)
                        }
                    }
                    .padding(8)
                }
            }
        }
        .onAppear { model.start() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.start() } else { model.stop() }
        }
        // 屋外で山を見比べている間に画面が消えないようにする(設定で選んだときだけ)。
        // @Published は購読した時点の値も流すので、起動時の設定もここで反映される。
        .onReceive(model.$settings.map(\.keepScreenOn).removeDuplicates()) { on in
            UIApplication.shared.isIdleTimerDisabled = on
        }
        .alert("山データの取得", isPresented: .constant(!model.settings.peakDataAsked)) {
            Button("あとで") { model.answerPeakDataPrompt(allow: false) }
            Button("取得する") { model.answerPeakDataPrompt(allow: true) }
        } message: {
            Text(
                "全国の山の名前・位置・標高（OpenStreetMap のデータ、約 0.5 MB）を取得します。" +
                    "取得したデータは端末に保存するので、圏外でも使えます。\n\n" +
                    "今すぐ取得しますか？「あとで」を選んだときは、設定画面から取得できます。"
            )
        }
        .fetchErrorAlert(model)
        // シートを開いている間は下の画面からアラートを出せないので、シートの中身にも付ける。
        .sheet(isPresented: $showSettings) {
            SettingsView(model: model).fetchErrorAlert(model)
        }
        .sheet(isPresented: $showDownload) {
            AreaDownloadView(model: model, download: model.areaDownload).fetchErrorAlert(model)
        }
        .sheet(item: selectedMountain) { nearby in
            MountainDetailView(nearby: nearby).fetchErrorAlert(model)
        }
        .sheet(isPresented: showGroup) {
            PeakGroupView(peaks: group).fetchErrorAlert(model)
        }
        // 開いている間も歩けば値が更新される。
        .sheet(isPresented: $showObserver) {
            ObserverDetailView(model: model).fetchErrorAlert(model)
        }
    }

    /// 手動位置モードの表示(上部の目盛りを隠し、向きの表示とコンパスを出す)。現在地へ戻り始めたらすぐ戻す。
    private var manualChrome: Bool { model.exploring && !model.returning }

    /// ヘディングアップと手動位置モードを切り替えるときの、方位目盛り・コンパス・視野の扇の動き。
    private let modeAnimation = Animation.easeInOut(duration: 0.35)

    /// 手動位置モードの左上に出す、端末の向きと現在地の標高。
    /// ヘディングアップの方位目盛りの下の札と同じ見た目(淡い白の札、方位は濃い色、標高は灰色)にする。
    private var headingLabel: some View {
        let parts = model.heading.map { readoutParts(headingDeg: $0, altitudeM: model.gpsLocation?.mslAltitudeM) }
        let text = parts.map { Text("向き \($0.direction)").foregroundColor(tapeInk) + Text($0.altitude).foregroundColor(tapeSubtle) }
            ?? Text("方位を取得中").foregroundColor(tapeInk)
        return text
            .font(.system(size: 15 * model.settings.textScale, weight: .bold))
            .padding(.horizontal, 12)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.white.opacity(0.5)))
    }

    /// 左下: 設定と事前ダウンロード。右下のモード切替ボタンと同じ見た目にする。
    private var bottomButtons: some View {
        HStack(spacing: 12) {
            RoundButton(label: "設定") {
                Image(systemName: "gearshape.fill")
            } action: {
                showSettings = true
            }
            if Features.areaDownload {
                AreaDownloadButton(download: model.areaDownload) { showDownload = true }
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

    /// まとめた山のうち、今の一覧にある山。取り直しで消えた山は除く。
    private var group: [NearbyMountain] {
        (groupIds ?? []).compactMap { id in model.mountains.first { $0.mountain.osmId == id } }
    }

    /// 取り直しで一覧の山がすべて消えたら、一覧のシートを閉じる。
    private var showGroup: Binding<Bool> {
        Binding(
            get: { groupIds != nil && !group.isEmpty },
            set: { if !$0 { groupIds = nil } }
        )
    }

    private var statusMessage: String? {
        if model.location == nil { return "現在地を取得しています…" }
        if model.heading == nil { return "方位センサーの値を待っています…" }
        if model.peakDataUpdating { return "山データを取得中…" }
        if model.loading { return "山データを読み込み中…" }
        // 取得半径(表示範囲より広い)の中に未取得の区画があると incomplete になる。欠けているのはたいてい取得半径の外縁なので、周辺に保存済みの山があれば「周辺の一部」と言う。
        let missing = model.mountains.isEmpty && model.summit == nil ? "この付近の山データがありません" : "周辺の一部の山データがありません"
        if model.incomplete { return model.peakData == nil ? "\(missing)。設定画面から取得できます" : missing }
        return nil
    }
}

/// 通信に失敗したら画面中央で知らせる。方位を待つ表示などに隠れて気づけないことがないようにする。
private struct FetchErrorAlert: ViewModifier {
    @ObservedObject var model: DialModel

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

/// 方位盤の下の角に置く丸いボタン。右下のモード切替ボタンと同じく、白地に影を付けて地図の上でも見分けやすくする。
private struct RoundButton<Content: View>: View {
    let label: String
    @ViewBuilder let content: () -> Content
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            content()
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(Color.gray)
                .frame(width: 56, height: 56)
                .background(Circle().fill(Color.white))
                .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// 事前ダウンロードの画面を開くボタン。ダウンロード中は画面を閉じていても進み具合が分かるよう、ボタンに出す。
private struct AreaDownloadButton: View {
    @ObservedObject var download: AreaDownloadModel
    let action: () -> Void

    var body: some View {
        RoundButton(label: "山データの事前ダウンロード") {
            if let running = download.running, running.progress.doneTiles > 0 {
                ProgressView(value: running.progress.fraction)
                    .progressViewStyle(.circular)
            } else if download.running != nil {
                // 最初の区画が終わるまでは進みが 0 なので、回り続ける表示にする。
                ProgressView()
            } else {
                Image(systemName: "arrow.down.circle")
            }
        } action: {
            action()
        }
    }
}

private struct StatusLine: View {
    let message: String?
    var labeled = false

    var body: some View {
        if let message {
            Group {
                if labeled {
                    Text(message).font(.footnote).foregroundStyle(.black)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.white.opacity(0.5)))
                } else {
                    Text(message).font(.footnote).foregroundStyle(.black)
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
        .mediumDetent()
    }
}

/// 重なる山をまとめた代表の山をタップしたときの一覧。山を選ぶと、その山の詳細を開く。
private struct PeakGroupView: View {
    let peaks: [NearbyMountain]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        // iOS 15 でも使えるよう NavigationView にする。
        NavigationView {
            List(peaks) { nearby in
                NavigationLink {
                    MountainDetailView(nearby: nearby)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nearby.mountain.name).font(.body)
                        Text("\(nearby.mountain.elevationText)・\(distanceText(nearby.distanceKm))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("この付近の山")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
        .mediumDetent()
    }
}

/// 双眼鏡(現在地)をタップしたときの詳細。距離は常に 0 なので出さない。
private struct ObserverDetailView: View {
    @ObservedObject var model: DialModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("現在地").font(.title2.bold())
                Spacer()
                Button("閉じる") { dismiss() }
            }
            if let here = model.gpsLocation {
                DetailRow(label: "緯度経度", value: coordinateText(latitude: here.latitude, longitude: here.longitude))
                DetailRow(label: "標高", value: elevationText(here.mslAltitudeM))
            }
            Spacer()
        }
        .padding(24)
        .mediumDetent()
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

private extension View {
    /// 詳細のシートを半分の高さで出す。iOS 15 には高さの指定がないので、全画面のシートになる。
    @ViewBuilder
    func mediumDetent() -> some View {
        if #available(iOS 16, *) {
            presentationDetents([.medium])
        } else {
            self
        }
    }
}
