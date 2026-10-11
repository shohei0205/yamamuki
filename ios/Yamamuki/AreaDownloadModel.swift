import Foundation
import os
import YamamukiCore

/// 事前ダウンロードを終えた地域。
struct SavedArea: Identifiable, Equatable {
    let prefecture: Prefecture
    let downloadedAt: Date
    /// ダウンロードした時点の山の数。
    let mountainCount: Int

    var id: Int { prefecture.code }
}

/// ダウンロード中の地域と進み具合。
struct RunningDownload: Equatable {
    let prefecture: Prefecture
    var progress: DownloadProgress {
        didSet { since = Date() }
    }
    /// progress を受け取った時刻。待っている秒数を数えるのに使う。
    private(set) var since = Date()

    init(prefecture: Prefecture, progress: DownloadProgress) {
        self.prefecture = prefecture
        self.progress = progress
    }
}

/// ダウンロードが終わった・中断した・失敗したことの知らせ。resume があれば続きから取得できる。
struct DownloadNotice: Equatable {
    let message: String
    var resume: Prefecture?
    var resumeRefresh = false
}

/// 事前ダウンロードした地域の一覧を UserDefaults に保存する。
/// 山データそのものは通常のキャッシュ(タイルごとのファイル)に入り、ここでは「どの地域を残すか」だけを覚える。
struct SavedAreaStore {
    private let key = "savedAreas"
    private let defaults = UserDefaults.standard

    /// 都道府県の一覧(Prefecture.all)と同じ順。
    func load() -> [SavedArea] {
        let raw = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        return raw.compactMap { code, value in
            guard let prefecture = Int(code).flatMap(Prefecture.byCode) else { return nil }
            let parts = value.split(separator: ",")
            guard let at = parts.first.flatMap({ Double($0) }) else { return nil }
            let count = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
            return SavedArea(prefecture: prefecture, downloadedAt: Date(timeIntervalSince1970: at), mountainCount: count)
        }
        .sorted { (Prefecture.all.firstIndex(of: $0.prefecture) ?? 0) < (Prefecture.all.firstIndex(of: $1.prefecture) ?? 0) }
    }

    func put(_ area: SavedArea) {
        var raw = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        raw[String(area.prefecture.code)] = "\(area.downloadedAt.timeIntervalSince1970),\(area.mountainCount)"
        defaults.set(raw, forKey: key)
    }

    func remove(_ prefecture: Prefecture) {
        var raw = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        raw[String(prefecture.code)] = nil
        defaults.set(raw, forKey: key)
    }

    private let pendingKey = "pendingAreaDownload"

    /// 途中で終わったダウンロード(アプリを閉じた・失敗した・中断した)。次に開いたときに続きから再開できるよう覚えておく。
    var pending: (prefecture: Prefecture, refresh: Bool)? {
        get {
            guard let raw = defaults.dictionary(forKey: pendingKey),
                  let prefecture = (raw["code"] as? Int).flatMap(Prefecture.byCode) else { return nil }
            return (prefecture, raw["refresh"] as? Bool ?? false)
        }
        nonmutating set {
            if let newValue {
                defaults.set(["code": newValue.prefecture.code, "refresh": newValue.refresh], forKey: pendingKey)
            } else {
                defaults.removeObject(forKey: pendingKey)
            }
        }
    }
}

/// 山データの事前ダウンロード(Android 版の AreaDownloadViewModel に相当)。
/// 画面を閉じてもダウンロードは続くよう、方位盤の DialModel が持つ。同時に進めるのは 1 地域だけ。
final class AreaDownloadModel: ObservableObject {
    /// 取得済みのタイルを取り直さずに使う期間(30 日)。設定の「取得したデータを使う期間」をなくしたので固定にした。
    static let maxAge: TimeInterval = 30 * 24 * 60 * 60

    @Published private(set) var savedAreas: [SavedArea]
    @Published private(set) var running: RunningDownload?
    @Published private(set) var notice: DownloadNotice?

    /// キャッシュを書き換えたとき(ダウンロードの終了・中断・失敗、地域の削除)。方位盤はキャッシュを読み直す。
    var onCacheChanged: (() -> Void)?

    private let repository: MountainRepository
    private let cache: FileMountainCache
    private let store = SavedAreaStore()
    private var task: Task<Void, Never>?
    /// 始めたダウンロードごとに増やす。中断したあとに古いタスクが遅れて状態を書き換えないよう、自分の番のときだけ書く。
    private var generation = 0
    /// 直前に知らされた取得済みの区画数。区画を書き込んだかどうかを見分ける。
    private var lastDoneTiles = -1
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "AreaDownload")

    init(repository: MountainRepository, cache: FileMountainCache) {
        self.repository = repository
        self.cache = cache
        savedAreas = store.load()
        // 前回アプリを閉じたときなどに途中で終わっていたら、続きから再開できるよう知らせる。
        if let pending = store.pending {
            notice = DownloadNotice(
                message: Strings.format("area_notice_pending", pending.prefecture.name),
                resume: pending.prefecture,
                resumeRefresh: pending.refresh
            )
        }
    }

    /// 保存済みの地域と、途中で終わった地域のタイル。キャッシュを消去しても残す(続きから再開できるように)。
    var savedTiles: Set<Tile> {
        Set((savedAreas.map(\.prefecture) + [store.pending?.prefecture].compactMap { $0 }).flatMap(\.tiles))
    }

    /// prefecture をダウンロードする。取得済みで新しいタイルは飛ばすので、中断や失敗のあとは続きから取得する。
    /// - Parameter refresh: 保存済みの地域を取り直す(取得済みのタイルも問い合わせる)。
    func start(_ prefecture: Prefecture, refresh: Bool = false, maxAge: TimeInterval) {
        guard running == nil else { return }
        generation += 1
        let id = generation
        store.pending = (prefecture, refresh)
        lastDoneTiles = -1
        running = RunningDownload(prefecture: prefecture, progress: DownloadProgress(doneTiles: 0, totalTiles: prefecture.tiles.count))
        notice = nil
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let count = try await repository.downloadTiles(prefecture.tiles, forceRefresh: refresh, maxAge: maxAge) { progress in
                    await MainActor.run { [weak self] in
                        guard let self, generation == id else { return }
                        running?.progress = progress
                        // 区画を書き込んだときだけ、方位盤に読み直してもらう(最初の知らせと取り直しの知らせでは書いていない)。
                        if lastDoneTiles >= 0 && progress.doneTiles > lastDoneTiles { onCacheChanged?() }
                        lastDoneTiles = progress.doneTiles
                    }
                }
                guard generation == id, !Task.isCancelled else { return }
                store.pending = nil
                store.put(SavedArea(prefecture: prefecture, downloadedAt: Date(), mountainCount: count))
                savedAreas = store.load()
                running = nil
                notice = DownloadNotice(message: Strings.format("area_notice_done", prefecture.name, groupedInteger(count)))
            } catch {
                // 中断したときは cancel() が知らせを出している。
                if error is CancellationError || Task.isCancelled || generation != id { return }
                logger.warning("事前ダウンロードに失敗: \(String(describing: error), privacy: .public)")
                let done = running?.progress
                running = nil
                notice = DownloadNotice(
                    message: done.map {
                        Strings.format("area_notice_failed_progress", prefecture.name, $0.doneTiles, $0.totalTiles)
                    } ?? Strings.format("area_notice_failed", prefecture.name),
                    resume: prefecture,
                    resumeRefresh: refresh
                )
            }
        }
    }

    /// ダウンロードを中断する。取得済みの区画は残り、もう一度ダウンロードすると続きから取得する。
    func cancel() {
        guard let running else { return }
        generation += 1
        task?.cancel()
        self.running = nil
        notice = DownloadNotice(
            message: Strings.format(
                "area_notice_canceled", running.prefecture.name, running.progress.doneTiles, running.progress.totalTiles
            ),
            resume: running.prefecture,
            resumeRefresh: store.pending?.refresh ?? false
        )
    }

    /// 知らせを閉じる。途中で終わったダウンロードの知らせなら、再開の案内もやめる。
    func dismissNotice() {
        if notice?.resume != nil { store.pending = nil }
        notice = nil
    }

    /// 保存済みの地域を消す。ほかの保存済みの地域と重なる区画は残す。通信しないので圏外でもできる。
    func delete(_ area: SavedArea) {
        guard running == nil else { return }
        store.remove(area.prefecture)
        savedAreas = store.load()
        let keep = savedTiles
        let tiles = area.prefecture.tiles.filter { !keep.contains($0) }
        Task { @MainActor [weak self] in
            guard let self else { return }
            await cache.remove(tiles)
            onCacheChanged?()
        }
    }
}
