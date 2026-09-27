import UIKit
import WebKit
import UniformTypeIdentifiers

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = GameController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

final class GameController: UIViewController, UIDocumentPickerDelegate, WKNavigationDelegate {
    private var importer: GameImport!
    private var server: AssetServer?
    private var web: WKWebView?
    private let status = UILabel()
    private let unlock = UIButton(type: .system)
    override var prefersStatusBarHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        status.textColor = .white; status.numberOfLines = 0; status.textAlignment = .center
        unlock.setTitle("Unlock with my Gameplay0.data", for: .normal)
        unlock.addTarget(self, action: #selector(selectAtlas), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [status, unlock])
        stack.axis = .vertical; stack.spacing = 20; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor), stack.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.8)])
        guard let assets = Bundle.main.resourceURL?.appendingPathComponent("assets"),
              let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { fail("App resources unavailable"); return }
        importer = GameImport(assets: assets, destination: support.appendingPathComponent("owned-game"))
        if importer.ready { launch() }
        else { status.text = "Select Content/Graphics/Atlases/Gameplay0.data from your own Celeste installation. The encrypted game data is included in this app." }
    }
    @objc private func selectAtlas() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
        picker.delegate = self; picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let atlas = urls.first else { return }
        unlock.isEnabled = false; status.text = "Verifying and decrypting…"
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                try importer.unlock(atlas) { message in DispatchQueue.main.async { self.status.text = message } }
                DispatchQueue.main.async { self.launch() }
            } catch { DispatchQueue.main.async { self.fail(error.localizedDescription) } }
        }
    }
    private func fail(_ message: String) {
        web?.isHidden = true; status.text = message; unlock.isEnabled = true
    }
    private func launch() {
        unlock.isHidden = true; status.text = "Starting WebKit…"
        server = AssetServer(runtime: importer.assets.appendingPathComponent("CelesteRuntime"), owned: importer.destination)
        do {
            try server?.start { result in DispatchQueue.main.async {
                switch result {
                case .success(let url): self.open(url)
                case .failure(let error): self.fail(error.localizedDescription)
                }
            } }
        } catch { fail(error.localizedDescription) }
    }
    private func open(_ url: URL) {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let web = WKWebView(frame: view.bounds, configuration: configuration)
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        web.navigationDelegate = self
        web.scrollView.isScrollEnabled = false
        web.isOpaque = false; web.backgroundColor = .black
        self.web = web; view.addSubview(web)
        web.load(URLRequest(url: url))
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript("crossOriginIsolated && typeof SharedArrayBuffer !== 'undefined' && typeof WebAssembly !== 'undefined'") { result, error in
            if error != nil || (result as? Bool) != true {
                self.fail("This iOS WebKit runtime does not expose the shared WebAssembly memory required by Celeste. This experimental wrapper cannot start on this device.")
            }
        }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        fail("iOS terminated the game’s WebKit process. Restart the app; this device may not have enough memory for this WASM build.")
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if url.host == "127.0.0.1" || ["blob", "about"].contains(url.scheme ?? "") { decisionHandler(.allow) }
        else {
            if ["https", "http"].contains(url.scheme ?? "") { UIApplication.shared.open(url) }
            decisionHandler(.cancel)
        }
    }
}
