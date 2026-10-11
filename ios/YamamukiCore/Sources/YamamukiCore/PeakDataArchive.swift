import Foundation

/// 端末に残した、取り込んだときの manifest とデータ本体(gz)の組。
public struct ArchivedPeakData: Equatable, Sendable {
    public let manifest: Data
    public let data: Data

    public init(manifest: Data, data: Data) {
        self.manifest = manifest
        self.data = data
    }
}

/// 取り込んだ配信データの manifest と本体(gz)を、端末に 1 組だけ残しておく場所。
/// アプリが読まない項目(manifest の出典、地点の tags など)も含めて残し、読み込み処理の版が上がったら、
/// 通信せずに保存データを作り直すのに使う。
public protocol PeakDataArchive: Sendable {
    /// 残した manifest と gz。どちらかが無いか読めなければ nil。
    func read() async -> ArchivedPeakData?
    /// 前の組を `manifest` と `data` で置き換える。失敗しても投げない(次の作り直しで SHA-256 が合わず、取り直すだけ)。
    func write(manifest: Data, data: Data) async
    /// 残した組を消す。
    func delete() async
}

/// `directory` に manifest.json と osm-peaks.json.gz を残す。書くときは一時ファイルに書いてから置き換え(`.atomic`)、
/// 書きかけのファイルを残さない。2 つの置き換えの間で止まったときは組が食い違うが、
/// 取り込み済みの記録の SHA-256 と合わなくなるので、作り直しには使われない。
public struct FilePeakDataArchive: PeakDataArchive {
    public static let manifestName = "manifest.json"
    public static let dataName = "osm-peaks.json.gz"

    private let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    private var manifestUrl: URL { directory.appendingPathComponent(Self.manifestName) }
    private var dataUrl: URL { directory.appendingPathComponent(Self.dataName) }

    public func read() async -> ArchivedPeakData? {
        guard let manifest = try? Data(contentsOf: manifestUrl), let data = try? Data(contentsOf: dataUrl) else { return nil }
        return ArchivedPeakData(manifest: manifest, data: data)
    }

    public func write(manifest: Data, data: Data) async {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? manifest.write(to: manifestUrl, options: .atomic)
        try? data.write(to: dataUrl, options: .atomic)
    }

    public func delete() async {
        try? FileManager.default.removeItem(at: manifestUrl)
        try? FileManager.default.removeItem(at: dataUrl)
    }
}
