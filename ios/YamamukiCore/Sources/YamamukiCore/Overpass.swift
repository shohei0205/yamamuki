import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 山データの取得元。テストではフェイクに差し替える。
public protocol MountainRemoteSource: Sendable {
    func fetchPeaks(_ box: BoundingBox) async throws -> [Mountain]
}

public struct OverpassError: Error, CustomStringConvertible {
    public let message: String
    /// 失敗したエンドポイントごとの原因。
    public let causes: [Error]
    /// サーバーが返した HTTP ステータス。つながらなかったなど、応答がないときは nil。
    public let httpStatus: Int?

    public init(_ message: String, causes: [Error] = [], httpStatus: Int? = nil) {
        self.message = message
        self.causes = causes
        self.httpStatus = httpStatus
    }

    public var description: String {
        ([message] + causes.map { "  \($0)" }).joined(separator: "\n")
    }
}

/// OSM Overpass API から natural=peak / natural=volcano の名前付きノードを取得する。
/// 最初のエンドポイントが失敗したら次のミラーを試す。
public struct OverpassClient: MountainRemoteSource {
    /// 以前は 2 番目に overpass.kumi.systems(現 overpass.private.coffee)を置いていたが、問い合わせに応答せず
    /// 75 秒待ってタイムアウトするだけだったので外した(#34)。本家の 504・429 は同時に使える枠が空いていない
    /// という意味なので、別のサーバーに回すより、少し待って同じサーバーに問い合わせ直すほうが通りやすい。
    public static let defaultEndpoints = [
        URL(string: "https://overpass-api.de/api/interpreter")!,
    ]

    private let session: URLSession
    private let endpoints: [URL]
    private let userAgent: String

    public init(session: URLSession = OverpassClient.makeSession(), endpoints: [URL] = OverpassClient.defaultEndpoints, userAgent: String = "yamamuki-ios") {
        self.session = session
        self.endpoints = endpoints
        self.userAgent = userAgent
    }

    /// Overpass は集計が終わるまで応答を返さず 20 秒以上かかることがあるので、待ち時間を長めにとる。
    public static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 75
        config.timeoutIntervalForResource = 90
        return URLSession(configuration: config)
    }

    public func fetchPeaks(_ box: BoundingBox) async throws -> [Mountain] {
        let query = OverpassQuery.peaks(box)
        // どのエンドポイントがなぜ失敗したか追えるよう、すべての失敗を残す。
        var errors: [Error] = []
        for endpoint in endpoints {
            try Task.checkCancellation()
            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            request.httpBody = Data("data=\(formEncode(query))".utf8)
            do {
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard (200..<300).contains(status) else {
                    errors.append(OverpassError("HTTP \(status) from \(endpoint.absoluteString)", httpStatus: status))
                    continue
                }
                return try OverpassParser.parse(String(decoding: data, as: UTF8.self))
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch {
                errors.append(OverpassError("\(type(of: error)) from \(endpoint.absoluteString)", causes: [error]))
            }
        }
        throw OverpassError("All Overpass endpoints failed", causes: errors, httpStatus: (errors.last as? OverpassError)?.httpStatus)
    }

    private func formEncode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

public enum OverpassQuery {
    /// 返してもらう列。[OverpassParser] はこの見出しで列を探す。
    static let csvColumns = ["::id", "::lat", "::lon", "name", "\"name:ja\"", "ele"]

    /// 山頂ノードを、使う項目だけのタブ区切り(見出し行付き)で返させる。
    /// JSON (out body) だと出典・コメントなど不要なタグまで届くため、通信量が gzip 後でも 4 割ほど多い。
    public static func peaks(_ box: BoundingBox, timeoutSec: Int = 60) -> String {
        let bbox = [box.south, box.west, box.north, box.east]
            .map { String(format: "%.5f", $0) }
            .joined(separator: ",")
        return """
            [out:csv(\(csvColumns.joined(separator: ","));true;"\\t")][timeout:\(timeoutSec)];
            (
              node["natural"="peak"]["name"](\(bbox));
              node["natural"="volcano"]["name"](\(bbox));
            );
            out;
            """
    }
}

public enum OverpassParser {
    /// [OverpassQuery.peaks] の応答(タブ区切り、1 行目が "@id  @lat  @lon  name  name:ja  ele" の見出し)を読む。
    /// 値に改行を含むなどで列数が合わない行は読み飛ばす。
    public static func parse(_ body: String) throws -> [Mountain] {
        var lines = body.split(whereSeparator: \.isNewline)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .makeIterator()
        guard let headerLine = lines.next() else { return [] }
        let header = headerLine.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
        func column(_ name: String) throws -> Int {
            guard let i = header.firstIndex(of: name) else {
                throw OverpassError("Overpass の応答に列 \(name) がありません: \(header.joined(separator: " "))")
            }
            return i
        }
        let id = try column("@id")
        let lat = try column("@lat")
        let lon = try column("@lon")
        let name = try column("name")
        let nameJa = try column("name:ja")
        let ele = try column("ele")

        var mountains: [Mountain] = []
        var seen = Set<Int64>()
        while let line = lines.next() {
            let cells = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard cells.count == header.count else { continue }
            let ja = cells[nameJa].trimmingCharacters(in: .whitespaces)
            let displayName = ja.isEmpty ? cells[name].trimmingCharacters(in: .whitespaces) : ja
            guard !displayName.isEmpty,
                  let osmId = Int64(cells[id]),
                  let latitude = Double(cells[lat]),
                  let longitude = Double(cells[lon]),
                  !seen.contains(osmId)
            else { continue }
            seen.insert(osmId)
            mountains.append(Mountain(
                osmId: osmId,
                name: displayName,
                latitude: latitude,
                longitude: longitude,
                elevationM: parseElevation(cells[ele].isEmpty ? nil : cells[ele])
            ))
        }
        return mountains
    }

    private static let elevationPattern = try! NSRegularExpression(
        pattern: #"^(-?[\d,]*\.?\d+)\s*(m|meters?|metres?|ft|feet|')?$"#
    )

    /// ele タグを m 単位の数値にする。"3776", "3776 m", "3,776", "3776;3775", "12345 ft" などに対応。
    public static func parseElevation(_ raw: String?) -> Double? {
        guard let raw else { return nil }
        let first = String(raw.split(separator: ";", omittingEmptySubsequences: false).first ?? "")
            .trimmingCharacters(in: .whitespaces)
            .lowercased()
        let range = NSRange(first.startIndex..., in: first)
        guard let match = elevationPattern.firstMatch(in: first, range: range),
              let numberRange = Range(match.range(at: 1), in: first),
              let value = Double(first[numberRange].replacingOccurrences(of: ",", with: ""))
        else { return nil }
        let unit = Range(match.range(at: 2), in: first).map { String(first[$0]) }
        switch unit {
        case "ft", "feet", "'": return value * 0.3048
        default: return value
        }
    }
}
