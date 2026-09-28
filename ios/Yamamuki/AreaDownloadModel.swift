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
    var progress: DownloadProgress
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

    /// 都道府県コード順。
    func load() -> [SavedArea] {
        let raw = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        return raw.compactMap { code, value in
            guard let prefecture = Int(code).flatMap(Prefecture.byCode) else { return nil }
            let parts = value.split(separator: ",")
            guard let at = parts.first.flatMap({ Double($0) }) else { return nil }
            let count = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
            return SavedArea(prefecture: prefecture, downloadedAt: Date(timeIntervalSince1970: at), mountainCount: count)
        }
        .sorted { $0.prefecture.code < $1.prefecture.code }
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
}

/// 山データの事前ダウンロード(Android 版の AreaDownloadViewModel に相当)。
/// 画面を閉じてもダウンロードは続くよう、方位盤の DialModel が持つ。同時に進めるのは 1 地域だけ。
final class AreaDownloadModel: ObservableObject {
    @Published private(set) var savedAreas: [SavedArea]
    @Published private(set) var running: RunningDownload?
    @Published private(set) var notice: DownloadNotice?

    /// キャッシュを書き換えたとき(ダウンロードの終了・中断・失敗、地域の削除)。方位盤はキャッシュを読み直す。
    var onCacheChanged: (() -> Void)?

    private let repository: MountainRepository
    private let cache: FileMountainCache
    private let store = SavedAreaStore()
    private var task: Task<Void, Never>?
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "AreaDownload")

    init(repository: MountainRepository, cache: FileMountainCache) {
        self.repository = repository
        self.cache = cache
        savedAreas = store.load()
    }

    /// 保存済みの地域のタイル。キャッシュを消去しても残す。
    var savedTiles: Set<Tile> { Set(savedAreas.flatMap(\.prefecture.tiles)) }

    /// prefecture をダウンロードする。取得済みで新しいタイルは飛ばすので、中断や失敗のあとは続きから取得する。
    /// - Parameter refresh: 保存済みの地域を取り直す(取得済みのタイルも問い合わせる)。
    func start(_ prefecture: Prefecture, refresh: Bool = false, maxAge: TimeInterval) {
        guard running == nil else { return }
        running = RunningDownload(prefecture: prefecture, progress: DownloadProgress(doneTiles: 0, totalTiles: prefecture.tiles.count))
        notice = nil
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { onCacheChanged?() }
            do {
                let count = try await repository.downloadTiles(prefecture.tiles, forceRefresh: refresh, maxAge: maxAge) { progress in
                    await MainActor.run { [weak self] in
                        guard let self, running?.prefecture == prefecture else { return }
                        running?.progress = progress
                    }
                }
                store.put(SavedArea(prefecture: prefecture, downloadedAt: Date(), mountainCount: count))
                savedAreas = store.load()
                running = nil
                notice = DownloadNotice(message: "\(prefecture.name)のダウンロードが完了しました（山 \(groupedInteger(count)) 件）。")
            } catch {
                // 中断したときは cancel() が知らせを出している。
                if error is CancellationError || Task.isCancelled { return }
                logger.warning("事前ダウンロードに失敗: \(String(describing: error), privacy: .public)")
                let done = running?.progress
                running = nil
                notice = DownloadNotice(
                    message: "\(prefecture.name)のダウンロード中に通信に失敗しました"
                        + (done.map { "（\($0.doneTiles) / \($0.totalTiles) 区画まで保存済み）" } ?? "")
                        + "。サーバーが混み合っているか、電波が弱い可能性があります。",
                    resume: prefecture,
                    resumeRefresh: refresh
                )
            }
        }
    }

    /// ダウンロードを中断する。取得済みの区画は残り、もう一度ダウンロードすると続きから取得する。
    func cancel() {
        guard let running else { return }
        task?.cancel()
        self.running = nil
        notice = DownloadNotice(
            message: "\(running.prefecture.name)のダウンロードを中断しました（\(running.progress.doneTiles) / \(running.progress.totalTiles) 区画まで保存済み）。",
            resume: running.prefecture
        )
    }

    func dismissNotice() { notice = nil }

    /// 保存済みの地域を消す。ほかの保存済みの地域と重なる区画は残す。
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
