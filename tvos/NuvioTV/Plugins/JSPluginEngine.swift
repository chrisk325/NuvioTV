import Foundation
import JavaScriptCore
import NuvioShared
import SwiftSoup

/// Runs Nuvio local scraper plugins with JavaScriptCore, exposing the same native bridge as the
/// Android QuickJS runtime (PluginRuntime.kt): fetch, URL parsing, cheerio (via SwiftSoup),
/// crypto-js and the shared polyfill in Resources/plugin-polyfill.js.
final class JSPluginEngine: NSObject, PluginEngine {
    private let queue = DispatchQueue(label: "nuvio.plugins", qos: .utility, attributes: .concurrent)

    private static let polyfill = resource("plugin-polyfill")
    private static let cryptoJs = resource("crypto-js")
    private static let maxResponseBytes = 8 * 1024 * 1024

    private static func resource(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "js"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }

    func execute(
        code: String,
        callArgsJson: String,
        scraperId: String,
        settingsJson: String,
        tmdbApiKey: String,
        completion: @escaping (String) -> Void
    ) {
        queue.async {
            let result = Self.run(code: code, args: callArgsJson, scraperId: scraperId, settings: settingsJson, tmdbKey: tmdbApiKey)
            completion(result)
        }
    }

    private static func run(code: String, args: String, scraperId: String, settings: String, tmdbKey: String) -> String {
        guard let context = JSContext() else { return "[]" }
        var result = "[]"
        let dom = DomCache()

        context.exceptionHandler = { _, exception in
#if DEBUG
            print("[plugin \(scraperId)] \(exception?.toString() ?? "error")")
#endif
        }

        func define(_ name: String, _ block: @escaping @convention(block) () -> Any?) {
            context.setObject(block, forKeyedSubscript: name as NSString)
        }
        func argument(_ index: Int) -> String {
            let values = JSContext.currentArguments() as? [JSValue] ?? []
            guard index < values.count, !values[index].isUndefined, !values[index].isNull else { return "" }
            return values[index].toString() ?? ""
        }

        let noop: @convention(block) () -> Void = {}
        let console = JSValue(newObjectIn: context)
        for level in ["log", "error", "warn", "info", "debug"] {
            console?.setObject(noop, forKeyedSubscript: level as NSString)
        }
        context.setObject(console, forKeyedSubscript: "console" as NSString)

        define("__native_fetch") {
            Fetcher.fetch(
                url: argument(0), method: argument(1).isEmpty ? "GET" : argument(1),
                headersJson: argument(2).isEmpty ? "{}" : argument(2),
                bodyKind: argument(3).isEmpty ? "none" : argument(3), body: argument(4),
                followRedirects: argument(5).lowercased() != "false"
            )
        }
        define("__parse_url") { parseUrl(argument(0)) }
        define("__cheerio_load") { dom.load(argument(0)) }
        define("__cheerio_select") { dom.select(docId: argument(0), selector: argument(1)) }
        define("__cheerio_find") { dom.find(docId: argument(0), elementId: argument(1), selector: argument(2)) }
        define("__cheerio_text") { dom.text(ids: argument(1)) }
        define("__cheerio_html") { dom.html(docId: argument(0), elementId: argument(1)) }
        define("__cheerio_inner_html") { dom.html(docId: argument(0), elementId: argument(1)) }
        define("__cheerio_attr") { dom.attr(elementId: argument(1), name: argument(2)) }
        define("__cheerio_next") { dom.sibling(docId: argument(0), elementId: argument(1), next: true) }
        define("__cheerio_prev") { dom.sibling(docId: argument(0), elementId: argument(1), next: false) }
        define("__capture_result") {
            result = argument(0).isEmpty ? "[]" : argument(0)
            return nil
        }
        define("__get_scraper_id") { scraperId }
        define("__get_scraper_settings") { settings }
        define("__get_tmdb_api_key") { tmdbKey }
        define("__get_call_args") { args }

        // JavaScriptCore has no timers; plugins only use them for short waits.
        context.evaluateScript("""
            if (typeof setTimeout === 'undefined') {
                globalThis.setTimeout = function(fn) { var a = Array.prototype.slice.call(arguments, 2); Promise.resolve().then(function() { fn.apply(null, a); }); return 0; };
                globalThis.clearTimeout = function() {};
                globalThis.setInterval = function() { return 0; };
                globalThis.clearInterval = function() {};
            }
        """)
        context.evaluateScript(polyfill)
        context.evaluateScript(cryptoJs)
        context.evaluateScript("""
            var module = { exports: {} };
            var exports = module.exports;
            (function() {
            \(code)
            })();
        """)
        // Same call wrapper as Android; fetch is synchronous, so the promise chain settles while
        // JavaScriptCore drains its microtask queue at the end of evaluateScript.
        context.evaluateScript("""
            (async function() {
                try {
                    var getStreams = module.exports.getStreams || globalThis.getStreams;
                    if (!getStreams) { __capture_result(JSON.stringify([])); return; }
                    var args = JSON.parse(__get_call_args());
                    var result = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
                    __capture_result(JSON.stringify(result || []));
                } catch (e) {
                    __capture_result(JSON.stringify([]));
                }
            })();
        """)
        return result
    }

    private static func parseUrl(_ string: String) -> String {
        guard let components = URLComponents(string: string), let scheme = components.scheme else {
            return #"{"protocol":"","host":"","hostname":"","port":"","pathname":"/","search":"","hash":""}"#
        }
        let host = components.host ?? ""
        let port = components.port.map(String.init) ?? ""
        let object: [String: String] = [
            "protocol": "\(scheme):",
            "host": port.isEmpty ? host : "\(host):\(port)",
            "hostname": host,
            "port": port,
            "pathname": components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath,
            "search": components.percentEncodedQuery.map { "?\($0)" } ?? "",
            "hash": components.percentEncodedFragment.map { "#\($0)" } ?? "",
        ]
        return json(object)
    }

    fileprivate static func json(_ object: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }
}

/// Synchronous HTTP for the JS bridge (runs on the plugin queue, never the main thread).
private enum Fetcher {
    private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        return URLSession(configuration: config)
    }()

    private static let noRedirectSession = URLSession(configuration: .ephemeral, delegate: RedirectBlocker(), delegateQueue: nil)

    static func fetch(url: String, method: String, headersJson: String, bodyKind: String, body: String, followRedirects: Bool) -> String {
        func failure(_ message: String) -> String {
            JSPluginEngine.json(["ok": false, "status": 0, "statusText": message, "url": url, "body": "", "bodyBase64": "", "headers": [String: String]()])
        }
        guard let target = URL(string: url) else { return failure("Invalid URL") }
        var request = URLRequest(url: target)
        request.httpMethod = method.uppercased()
        if let data = headersJson.data(using: .utf8),
           let headers = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (key, value) in headers where key.lowercased() != "accept-encoding" {
                request.setValue("\(value)", forHTTPHeaderField: key)
            }
        }
        if request.value(forHTTPHeaderField: "User-Agent") == nil {
            request.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", forHTTPHeaderField: "User-Agent")
        }
        switch bodyKind {
        case "text": request.httpBody = body.data(using: .utf8)
        case "base64": request.httpBody = Data(base64Encoded: normalizeBase64(body))
        default: break
        }
        if request.httpBody != nil, request.value(forHTTPHeaderField: "Content-Type") == nil {
            request.setValue(method.uppercased() == "POST" ? "application/x-www-form-urlencoded" : "application/json",
                             forHTTPHeaderField: "Content-Type")
        }

        let semaphore = DispatchSemaphore(value: 0)
        var output = failure("Fetch failed")
        let task = (followRedirects ? session : noRedirectSession).dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            guard let http = response as? HTTPURLResponse else {
                output = failure(error?.localizedDescription ?? "Fetch failed")
                return
            }
            var bytes = data ?? Data()
            let truncated = bytes.count > JSPluginEngineLimits.maxBytes
            if truncated { bytes = bytes.prefix(JSPluginEngineLimits.maxBytes) }
            var headers: [String: String] = [:]
            for (key, value) in http.allHeaderFields {
                headers["\(key)".lowercased()] = "\(value)"
            }
            let text = String(data: bytes, encoding: .utf8) ?? String(decoding: bytes, as: UTF8.self)
            output = JSPluginEngine.json([
                "ok": (200..<300).contains(http.statusCode),
                "status": http.statusCode,
                "statusText": HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                "url": http.url?.absoluteString ?? url,
                "body": text,
                "bodyBase64": bytes.base64EncodedString(),
                "headers": headers,
                "truncated": truncated,
            ])
        }
        task.resume()
        if semaphore.wait(timeout: .now() + 30) == .timedOut {
            task.cancel()
            return failure("Timed out")
        }
        return output
    }

    private static func normalizeBase64(_ input: String) -> String {
        var s = input.replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return s
    }
}

private enum JSPluginEngineLimits {
    static let maxBytes = 8 * 1024 * 1024
}

/// cheerio bridge: documents and elements addressed by string ids, as on Android (Jsoup).
private final class DomCache {
    private var documents: [String: Document] = [:]
    private var order: [String] = []
    private var elements: [String: Element] = [:]
    private var counter = 0
    private let containsPattern = try? NSRegularExpression(pattern: #":contains\(["']([^"']+)["']\)"#)

    private func nextId(_ prefix: String) -> String {
        counter += 1
        return "\(prefix):\(counter)"
    }

    private func normalize(_ selector: String) -> String {
        guard let containsPattern else { return selector }
        let range = NSRange(selector.startIndex..., in: selector)
        return containsPattern.stringByReplacingMatches(in: selector, range: range, withTemplate: ":contains($1)")
    }

    private func ids(_ list: [Element], docId: String, tag: String) -> String {
        let ids = list.map { element -> String in
            let id = nextId("\(docId):\(tag)")
            elements[id] = element
            return id
        }
        return JSPluginEngine.json(ids)
    }

    func load(_ html: String) -> String {
        let id = UUID().uuidString
        documents[id] = (try? SwiftSoup.parse(html)) ?? Document("")
        order.append(id)
        if order.count > 8 {
            let evicted = order.removeFirst()
            documents[evicted] = nil
            elements = elements.filter { !$0.key.hasPrefix("\(evicted):") }
        }
        return id
    }

    func select(docId: String, selector: String) -> String {
        guard let document = documents[docId], !selector.isEmpty,
              let found = try? document.select(normalize(selector)) else { return "[]" }
        return ids(found.array(), docId: docId, tag: "sel")
    }

    func find(docId: String, elementId: String, selector: String) -> String {
        guard let element = elements[elementId], let found = try? element.select(normalize(selector)) else { return "[]" }
        return ids(found.array(), docId: docId, tag: "find")
    }

    func text(ids: String) -> String {
        ids.split(separator: ",").compactMap { elements[String($0)].flatMap { try? $0.text() } }.joined(separator: " ")
    }

    func html(docId: String, elementId: String) -> String {
        if elementId.isEmpty { return (try? documents[docId]?.html()) ?? "" }
        return (try? elements[elementId]?.html()) ?? ""
    }

    func attr(elementId: String, name: String) -> String {
        guard let value = try? elements[elementId]?.attr(name), !value.isEmpty else { return "__UNDEFINED__" }
        return value
    }

    func sibling(docId: String, elementId: String, next: Bool) -> String {
        guard let element = elements[elementId],
              let sibling = (try? (next ? element.nextElementSibling() : element.previousElementSibling())) ?? nil
        else { return "__NONE__" }
        let id = nextId("\(docId):\(next ? "next" : "prev")")
        elements[id] = sibling
        return id
    }
}
