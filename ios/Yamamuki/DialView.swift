// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Combine
import SwiftUI
import UIKit
import YamamukiCore

/// 方位盤の画面。位置情報の許可、現在地、方位をつないで [DialCanvasView] に渡す。
struct DialView: View {
    @ObservedObject var model: DialModel

    @Environment(\.scenePhase) private var scenePhase
    /// 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    @State private var selectedId: String?
    @State private var showSettings = false
    @State private var showDownload = false
    @State private var showObserver = false
    /// 重なる山をまとめた代表の山をタップしたときの一覧。ID で持ち、表示中の一覧から引く。
    @State private var groupIds: [String]?
    /// 検索ボタンや山を押したときの方位盤の高さ。検索や山の詳細から山頂へ移るときに、山頂を中央に置くのに使う
    /// (検索画面のキーボードやシートの影響を受けないよう、開く前に覚える)。
    @State private var viewpointCanvasHeight = 0.0

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                dialGround.ignoresSafeArea()
                // ステータスバーの裏は、ヘッダーの空の上端と同じ色で塗り、空がつながって見えるようにする。
                VStack(spacing: 0) {
                    headerSkyTop.frame(height: geometry.safeAreaInsets.top)
                    Spacer()
                }
                .ignoresSafeArea()

                DialCanvasView(
                    headingDeg: model.displayHeading,
                    mountains: model.mountains,
                    rangeKm: model.rangeKm,
                    summit: model.summit,
                    // 検索位置モードでは、山名は左上の札に出すので、山頂アイコンの横には出さない。
                    summitLabel: model.viewpoint == nil,
                    altitudeM: model.observer?.mslAltitudeM,
                    maxPeaks: model.settings.maxPeaks,
                    textScale: model.settings.textScale,
                    gridStyle: model.settings.gridStyle,
                    // 双眼鏡の位置。検索位置モードでは選んだ山頂。
                    observerLocation: model.observer,
                    viewportLocation: model.location,
                    compassHeading: model.heading ?? model.displayHeading,
                    headingUp: !model.exploring,
                    tapeHidden: manualChrome ? 1 : 0,
                    // 視野の扇は、現在地に戻り終えてから出す。
                    viewFanAlpha: model.exploring ? 0 : 1,
                    bottomBleed: geometry.safeAreaInsets.bottom,
                    onPan: { model.onPan(dx: $0, dy: $1, chartHeight: $2) },
                    onTransform: { model.onTransform(zoom: $0, rotation: $1, previous: $2, midpoint: $3, chartHeight: $4) },
                    onMountainTap: {
                        viewpointCanvasHeight = Double(geometry.size.height)
                        selectedId = $0.mountain.id
                    },
                    onGroupTap: {
                        viewpointCanvasHeight = Double(geometry.size.height)
                        groupIds = $0.map(\.mountain.id)
                    },
                    // 現在地を取れる前は出す値がないので開かない。
                    onObserverTap: { if model.gpsLocation != nil { showObserver = true } }
                )
                .animation(modeAnimation, value: manualChrome)
                .animation(modeAnimation, value: model.exploring)

                // 配布版では、ヘッダーの中央にバナー広告(320×50)を出す(Ads.enabled)。
                if Ads.enabled {
                    VStack {
                        AdBanner()
                            .frame(width: 320, height: 50)
                            .padding(.top, (CGFloat(DialGeometry.headerHeight) - 50) / 2)
                        Spacer()
                    }
                }

                if model.settings.peakDataAsked && !model.hasLocationPermission {
                    PermissionRequest(denied: model.authorization == .denied || model.authorization == .restricted) {
                        model.requestLocationPermission()
                    }
                } else if model.hasLocationPermission {
                    VStack {
                        // 方位センサーの精度が低いと、方位が数十度ずれたまま別の山の名前を出してしまうので、上部で知らせる。
                        StatusLine(
                            // 方位が無効なあいだは方位の値が届かないので、値の有無にかかわらず出す。
                            message: model.headingAccuracyLow ? Strings.text("dial_heading_accuracy_low") : nil,
                            // 距離の円が文字の後ろを通っても読めるよう、方位の札と同じ淡い白の札にする。
                            labeled: true
                        )
                        StatusLine(message: statusMessage)
                        // 古いデータのままでも使えるので、自動では取り直さず、いつ取りに行くかは利用者に任せる。
                        // どちらも設定の「更新を確認」と同じ流れにする(新しい版の大きさを見せて確認する。圏外なら通信せずに知らせる)。
                        if model.peakDataMissing {
                            // 初回のダウンロードを「あとで」にしたときに、設定画面を探さなくても取得できるようにする。
                            StatusLine(message: Strings.text("dial_peak_data_missing"))
                            PeakDataLabel(text: Strings.text("dial_peak_data_download"), identifier: "peakDataDownloadLabel") {
                                model.updatePeakData()
                            }
                        } else if model.peakDataRefreshNeeded {
                            PeakDataLabel(text: Strings.text("peak_data_refresh_needed"), identifier: "peakDataRefreshLabel") {
                                model.updatePeakData()
                            }
                        } else if model.peakDataUpdateLabelShown {
                            // 設定で自動の確認をオンにしたときに、新しい版が見つかったら出す。
                            PeakDataLabel(text: Strings.text("peak_data_update_found"), identifier: "peakDataUpdateLabel") {
                                model.updatePeakData()
                            }
                        }
                        Spacer()
                    }
                    .padding(.top, CGFloat(DialGeometry.chartTop))
                    .padding(.horizontal, 16)
                }

                // 手動位置モードでは、方位目盛りの代わりに左上の向きの表示と右上のコンパスを左右から出す。どちらもヘッダーの下に置く。
                VStack {
                    HStack(alignment: .top) {
                        if manualChrome {
                            // 検索位置モードでは、手動位置モードと見分けられるよう、向きの代わりに選んだ山の札を出す。
                            Group {
                                if let viewpoint = model.viewpoint {
                                    ViewpointLabel(mountain: viewpoint, headingDeg: model.heading, textScale: model.settings.textScale)
                                } else {
                                    headingLabel
                                }
                            }
                            .transition(.move(edge: .leading).combined(with: .opacity))
                        }
                        Spacer()
                        if manualChrome {
                            // 北が上なら地図を端末の向きに合わせ、それ以外なら北を上にする。方位が未取得なら北を上にするだけ。
                            let tapFacesDevice = model.heading != nil
                                && PanGeometry.compassTapFacesDevice(heading: model.displayHeading)
                            Button {
                                let width = Double(geometry.size.width), height = Double(geometry.size.height)
                                if tapFacesDevice {
                                    model.faceDevice(canvasWidth: width, canvasHeight: height)
                                } else {
                                    model.faceNorth(canvasWidth: width, canvasHeight: height)
                                }
                            } label: {
                                CompassIndicator(heading: model.lockedHeading ?? model.heading)
                            }
                            .buttonStyle(.plain)
                            .disabled(model.location == nil || Double(geometry.size.height) <= DialGeometry.chartInset)
                            .accessibilityLabel(Strings.text(tapFacesDevice ? "compass_face_device" : "compass_face_north"))
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                    Spacer()
                }
                .padding(.top, CGFloat(DialGeometry.headerHeight) + 8)
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
                            // 山の検索。どちらのモードからでも使える。
                            RoundButton(label: Strings.text("search_open"), iconColor: searchButtonIconColor) {
                                Image(systemName: "magnifyingglass")
                            } action: {
                                viewpointCanvasHeight = Double(geometry.size.height)
                                model.openSearch()
                            }
                            .disabled(Double(geometry.size.height) <= DialGeometry.chartInset)
                            .padding(.trailing, 8)

                            Button {
                                // 検索位置モードは手動位置モードへ、手動位置モードはヘディングアップへ、ヘディングアップは手動位置モードへ。
                                if model.viewpoint != nil {
                                    model.leaveViewpoint()
                                } else if model.exploring {
                                    model.resetCenter()
                                } else {
                                    model.enterManual()
                                }
                            } label: {
                                Image(systemName: model.exploring ? "scope" : "location.north.fill")
                                    .font(.system(size: 26, weight: .medium))
                                    .foregroundStyle(modeButtonEnabled(geometry) ? modeButtonIconColor : Color.gray.opacity(0.4))
                                    .frame(width: 56, height: 56)
                                    .background(Circle().fill(Color.white))
                                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
                            }
                            .buttonStyle(.plain)
                            .disabled(!modeButtonEnabled(geometry))
                            .accessibilityLabel(Strings.text(model.exploring ? "map_mode_to_heading_up" : "map_mode_to_manual"))
                            .accessibilityValue(Strings.text(model.viewpoint != nil ? "map_mode_search"
                                : model.exploring ? "map_mode_manual" : "map_mode_heading_up"))
                            .padding(.trailing, 8)

                            Text(model.dataset.attribution)
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
        // シートを開いている間は、ダウンロードの確認と通信の失敗の知らせをシートの中身からだけ出す。下の画面からも
        // 出そうとすると、シートの上に出た知らせとぶつかり、知らせがシートごと閉じてしまう。
        .peakDataAlerts(model, presents: !sheetShown)
        // シートを開いている間は下の画面からアラートを出せないので、シートの中身にも付ける。
        .sheet(isPresented: $showSettings) {
            SettingsView(model: model).peakDataAlerts(model)
        }
        .sheet(isPresented: $showDownload) {
            AreaDownloadView(model: model, download: model.areaDownload).peakDataAlerts(model)
        }
        .sheet(item: selectedMountain) { nearby in
            MountainDetailView(nearby: nearby, viewpoint: model.viewpoint) {
                selectedId = nil
                goToSummit(nearby.mountain)
            }
            .peakDataAlerts(model)
        }
        .sheet(isPresented: showGroup) {
            PeakGroupView(peaks: group, viewpoint: model.viewpoint) {
                groupIds = nil
                goToSummit($0)
            }
            .peakDataAlerts(model)
        }
        // 開いている間も歩けば値が更新される。
        .sheet(isPresented: $showObserver) {
            ObserverDetailView(model: model).peakDataAlerts(model)
        }
        .sheet(isPresented: showSearch) {
            MountainSearchView(model: model) { goToSummit($0) }
                .peakDataAlerts(model)
        }
    }

    /// 選んだ山頂を中心にした検索位置モードに移る。
    private func goToSummit(_ mountain: Mountain) {
        model.showFromMountain(mountain, canvasHeight: viewpointCanvasHeight)
    }

    /// 右下のモード切替ボタンを押せるか。検索位置モードからは、現在地が分からなくても抜けられるようにする。
    private func modeButtonEnabled(_ geometry: GeometryProxy) -> Bool {
        model.viewpoint != nil
            || (model.hasLocationPermission && model.gpsLocation != nil && Double(geometry.size.height) > DialGeometry.chartInset)
    }

    /// 検索画面のシート。下に引いて閉じたときも、読み込んだ山を手放す。
    private var showSearch: Binding<Bool> {
        Binding(
            get: { model.search != nil },
            set: { if !$0 { model.closeSearch() } }
        )
    }

    /// いずれかのシートを開いている。
    private var sheetShown: Bool {
        showSettings || showDownload || selectedMountain.wrappedValue != nil || showGroup.wrappedValue || showObserver
            || showSearch.wrappedValue
    }

    /// 手動位置モードの表示(上部の目盛りを隠し、向きの表示とコンパスを出す)。現在地へ戻り始めたらすぐ戻す。
    private var manualChrome: Bool { model.exploring && !model.returning }

    /// ヘディングアップと手動位置モードを切り替えるときの、方位目盛り・コンパス・視野の扇の動き。
    private let modeAnimation = Animation.easeInOut(duration: 0.35)

    /// 手動位置モードの左上に出す、端末の向きと現在地の標高。
    /// ヘディングアップの方位目盛りの下の札と同じ見た目(淡い白の札、方位は濃い色、標高は灰色)にする。
    private var headingLabel: some View {
        let parts = model.heading.map { readoutParts(headingDeg: $0, altitudeM: model.observer?.mslAltitudeM) }
        let text = parts.map {
            Text($0.direction).foregroundColor(tapeInk) + Text($0.altitude).foregroundColor(tapeSubtle)
        } ?? Text(Strings.text("dial_heading_loading")).foregroundColor(tapeInk)
        return text
            .font(.system(size: 15 * model.settings.textScale, weight: .bold))
            .padding(.horizontal, 12)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.white.opacity(0.5)))
    }

    /// 左下: 設定と事前ダウンロード。右下のモード切替ボタンと同じ見た目にする。
    private var bottomButtons: some View {
        HStack(spacing: 12) {
            RoundButton(label: Strings.text("settings_title"), iconColor: settingsButtonIconColor) {
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
                return model.mountains.first { $0.mountain.id == id }
                    ?? model.summit.flatMap { $0.mountain.id == id ? $0 : nil }
            },
            set: { if $0 == nil { selectedId = nil } }
        )
    }

    /// まとめた山のうち、今の一覧にある山。取り直しで消えた山は除く。
    private var group: [NearbyMountain] {
        (groupIds ?? []).compactMap { id in model.mountains.first { $0.mountain.id == id } }
    }

    /// 取り直しで一覧の山がすべて消えたら、一覧のシートを閉じる。
    private var showGroup: Binding<Bool> {
        Binding(
            get: { groupIds != nil && !group.isEmpty },
            set: { if !$0 { groupIds = nil } }
        )
    }

    private var statusMessage: String? {
        if model.location == nil { return Strings.text("dial_status_locating") }
        if model.heading == nil { return Strings.text("dial_status_waiting_heading") }
        if model.peakDataUpdating { return Strings.text("dial_status_fetching") }
        if model.loading { return Strings.text("dial_status_loading") }
        // 取得半径(表示範囲より広い)の中に未取得の区画があると incomplete になる。欠けているのはたいてい取得半径の外縁なので、周辺に保存済みの山があれば「周辺の一部」と言う。
        let missing = Strings.text(model.mountains.isEmpty && model.summit == nil ? "dial_status_no_data_here" : "dial_status_partial_data")
        // 山データを取り込んでいないときは、下のダウンロードのボタンと一緒に知らせるので、ここでは出さない。
        if model.incomplete { return model.peakDataMissing ? nil : missing }
        return nil
    }
}

/// 通信に失敗したら画面中央で知らせる。方位を待つ表示などに隠れて気づけないことがないようにする。
private struct FetchErrorAlert: ViewModifier {
    @ObservedObject var model: DialModel
    /// この画面から知らせを出す。シートの下になっている画面では false にする。
    var enabled = true

    func body(content: Content) -> some View {
        content.alert(
            Strings.text("peak_data_error_title"),
            isPresented: Binding(
                get: { enabled && model.fetchErrorMessage != nil },
                set: { if !$0 { model.dismissFetchError() } }
            )
        ) {
            Button(Strings.text("peak_data_error_retry")) { model.retryAfterFetchError() }
            Button(Strings.text("common_close"), role: .cancel) { model.dismissFetchError() }
        } message: {
            Text(model.fetchErrorMessage ?? "")
        }
    }
}

/// 新しい山データを作った日と大きさを知らせ、ダウンロードするかを聞く(初回の問い合わせのあとと、設定画面の確認から)。
private struct PeakDataOfferAlert: ViewModifier {
    @ObservedObject var model: DialModel
    /// この画面から確認を出す。シートの下になっている画面では false にする。
    var enabled = true

    func body(content: Content) -> some View {
        content.alert(
            Strings.text("peak_data_offer_title"),
            // 閉じるのはボタンの答えだけで行う(閉じる通知で「あとで」と扱うと、ダウンロードのボタンより先に届いたときに取り消してしまう)。
            isPresented: Binding(
                get: { enabled && model.peakDataOffer != nil },
                set: { _ in }
            )
        ) {
            Button(Strings.text("peak_data_offer_later"), role: .cancel) { model.answerPeakDataOffer(download: false) }
            Button(Strings.text("peak_data_offer_download")) { model.answerPeakDataOffer(download: true) }
        } message: {
            if let first = model.peakDataOfferFirst {
                Text(Strings.format("peak_data_offer_message_first", first.label, detail))
            } else {
                Text(Strings.format("peak_data_offer_message", detail))
            }
        }
    }
}

private extension PeakDataOfferAlert {
    /// 確認の 2 行目。データを作った日が版の文字列から読めなければ、大きさだけを出す。
    var detail: String {
        guard let manifest = model.peakDataOffer?.manifest else { return "" }
        let size = byteSizeText(Int64(manifest.sizeBytes))
        guard let date = manifest.releaseDate else { return Strings.format("peak_data_offer_size", size) }
        return Strings.format("peak_data_offer_detail", date, size)
    }
}

private extension View {
    /// 山データの通信に関わる知らせ(ダウンロードの確認と、通信の失敗)。
    func peakDataAlerts(_ model: DialModel, presents: Bool = true) -> some View {
        modifier(PeakDataOfferAlert(model: model, enabled: presents)).modifier(FetchErrorAlert(model: model, enabled: presents))
    }
}

/// 設定ボタンのアイコンの色(緑)。
private let settingsButtonIconColor = Color(hex: 0x43A047)
/// 事前ダウンロードのボタンのアイコンの色(橙)。
private let downloadButtonIconColor = Color(hex: 0xFB8C00)
/// モード切替ボタンのアイコンの色(青)。
private let modeButtonIconColor = Color(hex: 0x1A73E8)
/// 山の検索ボタンのアイコンの色(紫)。
private let searchButtonIconColor = Color(hex: 0x8E24AA)

/// 方位盤の下の角に置く丸いボタン。右下のモード切替ボタンと同じく、白地に影を付けて地図の上でも見分けやすくする。
/// アイコンはボタンごとに色を変える([iconColor])。
private struct RoundButton<Content: View>: View {
    let label: String
    let iconColor: Color
    @ViewBuilder let content: () -> Content
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            content()
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(iconColor)
                .tint(iconColor)
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
        RoundButton(label: Strings.text("dial_area_download"), iconColor: downloadButtonIconColor) {
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

/// 山データの取り直しが要ることや、新しい山データがあることを知らせるラベル。タップで更新を確かめる。
/// ほかの知らせと見分けやすいよう、濃い白の札にする。
private struct PeakDataLabel: View {
    let text: String
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "arrow.clockwise").foregroundStyle(downloadButtonIconColor)
                Text(text).foregroundStyle(tapeInk)
            }
            .font(.footnote.bold())
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color.white.opacity(0.9)))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

private struct PermissionRequest: View {
    /// 一度断られていると、iOS はアプリから許可の画面を出せないので、設定アプリに案内する。
    let denied: Bool
    let onRequest: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(Strings.text(denied ? "dial_permission_denied_message" : "dial_permission_message"))
            .multilineTextAlignment(.center)
            .foregroundStyle(.black)
            Button(Strings.text(denied ? "dial_permission_open_settings" : "dial_permission_allow")) {
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

/// タップした山の詳細。検索位置モード(viewpoint が非 nil)では、距離は選んだ山頂から測ったもの。
/// 「山頂へ移動」で、その山頂を中心にした検索位置モードに移る(onGoToSummit)。
private struct MountainDetailView: View {
    let nearby: NearbyMountain
    let viewpoint: Mountain?
    let onGoToSummit: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let m = nearby.mountain
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(m.name).font(.title2.bold())
                Spacer()
                Button(Strings.text("common_close")) { dismiss() }
            }
            DetailRow(label: Strings.text("detail_elevation"), value: elevationLabel(m.elevationM))
            DetailRow(label: Strings.text("detail_coordinate"), value: coordinateLabel(latitude: m.latitude, longitude: m.longitude))
            if let viewpoint {
                // 選んだ山頂そのものは、山頂からの距離が 0 なので出さない。
                if viewpoint.id != m.id {
                    DetailRow(label: Strings.text("detail_distance_from_summit"), value: distanceText(nearby.distanceKm))
                }
            } else {
                DetailRow(label: Strings.text("detail_distance"), value: distanceText(nearby.distanceKm))
            }
            // 選んだ山頂にいるときは、移る先が今の場所と同じなので出さない。
            if viewpoint?.id != m.id {
                Button(Strings.text("detail_go_to_summit"), action: onGoToSummit)
            }
            Spacer()
        }
        .padding(24)
        .mediumDetent()
    }
}

/// 検索位置モードの左上に出す、選んだ山の札。山頂の標識をまねた木の横板に、山名と標高、緯度経度を書き、
/// 支柱の横の白い小札に端末の向きを出す。向きは手動位置モードの左上の表示と同じく、端末が向いている方位にする。
/// そのまま画面を撮っても、どの山から見た方位盤かが分かるようにする。
private struct ViewpointLabel: View {
    let mountain: Mountain
    let headingDeg: Double?
    let textScale: Double

    var body: some View {
        let lat = Strings.format(mountain.latitude >= 0 ? "detail_latitude_north" : "detail_latitude_south", degreeText(mountain.latitude))
        let lon = Strings.format(mountain.longitude >= 0 ? "detail_longitude_east" : "detail_longitude_west", degreeText(mountain.longitude))
        let heading = headingDeg.map { readoutParts(headingDeg: $0, altitudeM: nil).direction }
            ?? Strings.text("dial_heading_loading")
        let board = RoundedRectangle(cornerRadius: 6)
        VStack(alignment: .leading, spacing: 0) {
            // 木の板。木目と、左右のビスを描く。
            VStack(alignment: .leading, spacing: 1) {
                (Text(mountain.name).font(.system(size: 21 * textScale, weight: .black, design: .serif)).kerning(21 * textScale * 0.12)
                    + Text("  " + elevationListLabel(mountain.elevationM)).font(.system(size: 12.5 * textScale, weight: .bold)))
                    .foregroundColor(woodInk)
                Text(verbatim: "\(lat)  \(lon)").font(.system(size: 11 * textScale, weight: .medium)).foregroundColor(woodInkSubtle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(
                board.fill(LinearGradient(colors: [woodLight, woodDark], startPoint: .top, endPoint: .bottom))
                    .overlay(WoodGrain().clipShape(board))
                    .overlay(board.strokeBorder(woodEdge, lineWidth: 2))
                    .shadow(color: .black.opacity(0.3), radius: 2, y: 2)
            )
            // 支柱と、その横の向きの小札。
            HStack(alignment: .top, spacing: 10) {
                LinearGradient(colors: [postDark, postLight], startPoint: .leading, endPoint: .trailing)
                    .frame(width: 10, height: 14)
                    .overlay(HStack(spacing: 0) { woodEdge.frame(width: 1); Spacer(minLength: 0); woodEdge.frame(width: 1) })
                    .padding(.leading, 24)
                Text(heading)
                    .font(.system(size: 12 * textScale, weight: .bold))
                    .foregroundColor(tapeInk)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 1)
                    .background(Capsule().fill(Color.white).shadow(color: .black.opacity(0.25), radius: 1.5, y: 1))
                    .padding(.top, 3)
            }
        }
        // 山名の長さによらず、札の幅は決まった幅にする。撮った画面の見た目がそろう。長い山名は折り返す。
        .frame(width: 260, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// 札の板の木目(細い縦の筋)と、左右の端のビス。
private struct WoodGrain: View {
    var body: some View {
        Canvas { ctx, size in
            var x: CGFloat = 2.5
            var i = 0
            while x < size.width {
                // 筋の濃さと間隔を少しずつ変えて、木目らしくする。文字が読みにくくならないよう、筋は薄くする。
                let light = i % 3 == 0
                ctx.fill(Path(CGRect(x: x, y: 0, width: light ? 3 : 2, height: size.height)),
                         with: .color(light ? .white.opacity(0.04) : .black.opacity(0.025)))
                x += 5 + CGFloat(i % 4)
                i += 1
            }
            let r: CGFloat = 2.5
            for cx in [6.5, size.width - 6.5] {
                ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: size.height / 2 - r, width: r * 2, height: r * 2)), with: .color(woodScrew))
                ctx.fill(Path(ellipseIn: CGRect(x: cx - r / 3 - 1, y: size.height / 2 - r / 3 - 1, width: 2, height: 2)), with: .color(.white.opacity(0.3)))
            }
        }
    }
}

private let woodLight = Color(hex: 0xE9CFA2)
private let woodDark = Color(hex: 0xDDBB86)
private let woodEdge = Color(hex: 0x7A5230)
private let woodScrew = Color(hex: 0x5B3A1E)
private let woodInk = Color(hex: 0x1E140A)
private let woodInkSubtle = Color(hex: 0x2B1D10)
private let postDark = Color(hex: 0x8A6036)
private let postLight = Color(hex: 0xA57646)

/// 重なる山をまとめた代表の山をタップしたときの一覧。山を選ぶと、その山の詳細を開く。
private struct PeakGroupView: View {
    let peaks: [NearbyMountain]
    /// 検索位置モードで選んだ山。詳細の距離の見出しを変えるのに使う。
    let viewpoint: Mountain?
    /// 一覧から開いた詳細で「山頂へ移動」を押したとき。
    let onGoToSummit: (Mountain) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        // iOS 15 でも使えるよう NavigationView にする。
        NavigationView {
            List(peaks) { nearby in
                NavigationLink {
                    MountainDetailView(nearby: nearby, viewpoint: viewpoint) { onGoToSummit(nearby.mountain) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nearby.mountain.name).font(.body)
                        Text(Strings.format("detail_group_item", elevationListLabel(nearby.mountain.elevationM), distanceText(nearby.distanceKm)))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(Strings.text("detail_group_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Strings.text("common_close")) { dismiss() }
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
                Text(Strings.text("detail_observer_title")).font(.title2.bold())
                Spacer()
                Button(Strings.text("common_close")) { dismiss() }
            }
            if let here = model.gpsLocation {
                DetailRow(label: Strings.text("detail_coordinate"), value: coordinateLabel(latitude: here.latitude, longitude: here.longitude))
                DetailRow(label: Strings.text("detail_elevation"), value: elevationLabel(here.mslAltitudeM))
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
