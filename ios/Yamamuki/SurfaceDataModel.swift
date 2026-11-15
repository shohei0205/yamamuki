// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
import os
import YamamukiCore

/// 選択画面の「地表データ」の 1 行。
struct SurfaceDataEntry: Equatable {
    let dataset: SurfaceDataset
    /// 取得済みの版。まだなら nil。
    var installed: InstalledSurfaceData?
    /// カタログにある、まだ取り込んでいない版。
    var available: SurfaceManifest?

    /// 取得済みで、新しい版がある。
    var updateFound: Bool { installed != nil && available != nil }

    var experimental: Bool { available?.experimental ?? installed?.experimental ?? false }
}

/// ダウンロードするかを聞いている版。
struct SurfaceOffer: Equatable {
    let dataset: SurfaceDataset
    let manifest: SurfaceManifest
}

/// 地表データ(方位盤の背景に描く森林・水域・陰影)の確認・ダウンロード・削除。山データの選択画面と設定画面から使う。
/// 山データ(`DialModel`)とは別に持ち、方位盤の動きに影響しないようにする。
@MainActor
final class SurfaceDataModel: ObservableObject {
    /// 取得済みの種類と、カタログにある種類。並びは `SurfaceDataset` の順。
    @Published private(set) var entries: [SurfaceDataEntry] = []
    /// カタログで取得できる版を確かめている。
    @Published private(set) var checking = false
    /// ダウンロードしている種類。していなければ nil。
    @Published private(set) var updating: SurfaceDataset?
    /// ダウンロードの進み具合(0〜1)。
    @Published private(set) var progress = 0.0
    /// 大きさを見せて、ダウンロードするかを聞いている版。
    @Published var offer: SurfaceOffer?
    /// 取得に失敗したときの知らせ。
    @Published var errorMessage: String?

    private let stores: [SurfaceDataset: SurfaceDataStore]
    private let updaters: [SurfaceDataset: SurfaceDataUpdater]
    /// 使っている種類(設定の surfaceDataset)。
    private let selected: () -> SurfaceDataset
    /// 設定を書き換える(`DialModel.updateSettings`)。
    private let updateSettings: ((inout Settings) -> Void) -> Void
    /// 確認して見つけた、まだ取り込んでいない版。
    private var offers: [SurfaceDataset: SurfaceDataOffer] = [:]
    private var checkTask: Task<Void, Never>?
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "SurfaceDataModel")

    init(useDev: Bool, userAgent: String, catalogCache: PeakCatalogCache,
         selected: @escaping () -> SurfaceDataset, updateSettings: @escaping ((inout Settings) -> Void) -> Void) {
        var stores: [SurfaceDataset: SurfaceDataStore] = [:]
        var updaters: [SurfaceDataset: SurfaceDataUpdater] = [:]
        for d in SurfaceDataset.allCases {
            let store = SurfaceDataStore(dataset: d)
            stores[d] = store
            updaters[d] = SurfaceDataUpdater(
                source: CatalogSurfaceDataSource(dataset: d, useDev: useDev, userAgent: userAgent, catalogCache: catalogCache),
                files: store.files,
                dataset: d,
                validate: { file, manifest in try SurfaceDatabaseValidator.validate(file, manifest: manifest) }
            )
        }
        self.stores = stores
        self.updaters = updaters
        self.selected = selected
        self.updateSettings = updateSettings
        entries = installedEntries()
    }

    /// `dataset` を取得済みか。
    func installed(_ dataset: SurfaceDataset) -> Bool {
        entries.contains { $0.dataset == dataset && $0.installed != nil }
    }

    private func installedEntries() -> [SurfaceDataEntry] {
        SurfaceDataset.allCases.compactMap { d in stores[d]!.load().map { SurfaceDataEntry(dataset: d, installed: $0) } }
    }

    /// 選択画面を開いた。取得済みの種類はすぐ出し、通信できればカタログで取り込める版を確かめて足す。データ本体は取らない。
    /// 利用者が頼んだ通信ではないので、確かめられなくても知らせない(取得済みの種類はそのまま使える)。
    func open() {
        checkTask?.cancel()
        offers = [:]
        entries = installedEntries()
        guard updating == nil else { return }
        checking = true
        checkTask = Task { [weak self] in
            for d in SurfaceDataset.allCases {
                guard let self, !Task.isCancelled else { return }
                let installed = self.stores[d]!.load()
                var entry = installed.map { SurfaceDataEntry(dataset: d, installed: $0) }
                do {
                    switch try await self.updaters[d]!.check(installed: installed) {
                    case .upToDate(let checked):
                        self.stores[d]!.save(checked)
                        entry = SurfaceDataEntry(dataset: d, installed: checked)
                    case .available(let offer):
                        self.offers[d] = offer
                        entry = SurfaceDataEntry(dataset: d, installed: installed, available: offer.manifest)
                    case .unavailable:
                        break
                    }
                } catch {
                    self.logger.warning("地表データ(\(d.id, privacy: .public))の確認に失敗: \(String(describing: error), privacy: .public)")
                }
                if Task.isCancelled { return }
                self.setEntry(d, entry)
            }
            self?.checking = false
        }
    }

    /// 選択画面の `d` の行を `entry` にする。nil なら行を消す。
    private func setEntry(_ d: SurfaceDataset, _ entry: SurfaceDataEntry?) {
        let order = SurfaceDataset.allCases
        entries = (entries.filter { $0.dataset != d } + [entry].compactMap { $0 })
            .sorted { order.firstIndex(of: $0.dataset)! < order.firstIndex(of: $1.dataset)! }
    }

    /// 選択画面で `d` を選んだ。取得済みならその種類を使い、まだなら大きさを見せてダウンロードするかを聞く。
    func choose(_ d: SurfaceDataset) {
        guard updating == nil else { return }
        if stores[d]!.load() != nil {
            updateSettings { $0.surfaceDataset = d }
        } else {
            offerDownload(d)
        }
    }

    /// `d` の新しい版(または、まだ取得していない版)の大きさを見せて、ダウンロードするかを聞く。
    func offerDownload(_ d: SurfaceDataset) {
        guard updating == nil, let offer = offers[d] else { return }
        self.offer = SurfaceOffer(dataset: d, manifest: offer.manifest)
    }

    func answerOffer(download: Bool) {
        guard let d = offer?.dataset else { return }
        offer = nil
        if download { install(d) }
    }

    /// 確認した版のデータ本体を取得して端末に置く。初めての種類なら、取り込めたらその種類を使う。
    private func install(_ d: SurfaceDataset) {
        guard updating == nil, let offer = offers[d] else { return }
        checkTask?.cancel()
        checking = false
        updating = d
        progress = 0
        Task { [weak self] in
            guard let self else { return }
            do {
                let installed = try await self.updaters[d]!.install(offer) { p in
                    Task { @MainActor [weak self] in
                        // 進み具合は順不同で届くことがあるので、戻さない。
                        if let self, self.updating == d, p > self.progress { self.progress = p }
                    }
                }
                self.stores[d]!.save(installed)
                self.offers[d] = nil
                self.setEntry(d, SurfaceDataEntry(dataset: d, installed: installed))
                self.updateSettings { $0.surfaceDataset = d }
            } catch {
                self.logger.warning("地表データ(\(d.id, privacy: .public))の取得に失敗: \(String(describing: error), privacy: .public)")
                if let error = error as? SurfaceDataError {
                    self.errorMessage = Strings.format("surface_data_error_invalid", error.message)
                } else {
                    self.errorMessage = Strings.text("surface_data_error_network")
                }
            }
            self.updating = nil
        }
    }

    /// `d` の地表データを端末から消す。使っている種類なら、方位盤の背景は地表なしに戻る。
    func delete(_ d: SurfaceDataset) {
        guard updating == nil else { return }
        stores[d]!.clear()
        if selected() == d { updateSettings { $0.showSurface = false } }
        // 行は「未取得」にしておき、取り込める版が分かっていれば選べるようにする。
        if let known = offers[d] {
            setEntry(d, SurfaceDataEntry(dataset: d, installed: nil, available: known.manifest))
            return
        }
        setEntry(d, nil)
        // 開いたときの確認で最新だった種類は版を覚えていないので、ダウンロードし直せるように確かめ直す。
        Task { [weak self] in
            guard let self else { return }
            do {
                guard case .available(let offer) = try await self.updaters[d]!.check(installed: nil) else { return }
                self.offers[d] = offer
                if self.stores[d]!.load() == nil {
                    self.setEntry(d, SurfaceDataEntry(dataset: d, installed: nil, available: offer.manifest))
                }
            } catch {
                self.logger.warning("地表データ(\(d.id, privacy: .public))の確認に失敗: \(String(describing: error), privacy: .public)")
            }
        }
    }

    /// 使っている種類の地表データの出典。取り込んだときに端末へ残した manifest から読む。取得していなければ nil。
    func credits() -> PeakDataCredits? {
        let store = stores[selected()]!
        guard store.load() != nil, let manifest = store.files.readManifest() else { return nil }
        return PeakDataCreditsReader.parse(manifest)
    }
}
