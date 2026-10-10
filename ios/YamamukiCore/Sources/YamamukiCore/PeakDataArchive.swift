import Foundation

/// 取り込んだ配信データの本体(gz)を、端末に 1 つだけ残しておく場所。
/// アプリが読まない項目も含めて残し、読み込み処理の版が上がったら、通信せずに保存データを作り直すのに使う。
public protocol PeakDataArchive: Sendable {
    /// 残した gz。無いか読めなければ nil。
    func read() async -> Data?
    /// 前の gz を `data` で置き換える。失敗しても投げない(次の作り直しで SHA-256 が合わず、取り直すだけ)。
    func write(_ data: Data) async
    /// 残した gz を消す。
    func delete() async
}

/// `url` に gz を残す。書くときは一時ファイルに書いてから置き換え(`.atomic`)、書きかけのファイルを残さない。
public struct FilePeakDataArchive: PeakDataArchive {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func read() async -> Data? {
        try? Data(contentsOf: url)
    }

    public func write(_ data: Data) async {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    public func delete() async {
        try? FileManager.default.removeItem(at: url)
    }
}
