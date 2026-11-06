import SwiftUI
import AppKit

struct PackageRow: Identifiable {
    var id: String { package }
    let package: String
    let purpose: String
    let eligible: Bool
    let protected: Bool
}
final class Manager: ObservableObject {
    @Published var serial = ""
    @Published var output = "连接电视后扫描。未知型号和固件仅展示组件，不自动禁用。"
    @Published var rows: [PackageRow] = []
    @Published var selection = Set<String>()
    @Published var busy = false
    @Published var device = "尚未连接"
    func run(_ args: [String]) {
        guard !busy else { return }
        busy = true
        let serialValue = serial.trimmingCharacters(in: .whitespacesAndNewlines)
        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
            let resource = Bundle.main.resourceURL!
            process.arguments = [resource.appendingPathComponent("livingtv.py").path, "--serial", serialValue] + args
            let pipe = Pipe(); process.standardOutput = pipe; process.standardError = pipe
            var result = ""
            do {
                try process.run()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                result = String(data: data, encoding: .utf8) ?? "无法读取结果"
            } catch { result = "无法运行：\(error.localizedDescription)\n请确认系统 Python 3 可用。" }
            let text = result
            DispatchQueue.main.async {
                self.busy = false; self.output = text
                if let data = text.data(using:.utf8), let object = try? JSONSerialization.jsonObject(with:data) as? [String:Any],
                   let payload = object["data"] as? [String:Any] {
                    if let identity = payload["identity"] as? [String:Any] {
                        self.device = "\(identity["manufacturer"] ?? "") · \(identity["model"] ?? "")"
                    }
                    if let packages = payload["packages"] as? [[String:Any]] {
                        self.rows = packages.map { PackageRow(package: $0["package"] as? String ?? "", purpose: $0["purpose"] as? String ?? "", eligible: $0["eligible"] as? Bool ?? false, protected: $0["protected"] as? Bool ?? false) }
                        self.selection = []
                        let eligible = self.rows.filter { $0.eligible }.count
                        self.output = "扫描完成：\(self.rows.count) 个组件，\(eligible) 个可选组件。\n" + (payload["note"] as? String ?? "")
                        if let guardError = payload["guard_error"] as? String { self.output += "\n" + guardError }
                    } else if let results = payload["results"] as? [[String:Any]] {
                        self.output = results.map { item in
                            "\(item["package"] ?? "组件")：" + ((item["success"] as? Bool ?? false) ? "成功" : "失败 · \(item["error"] ?? "未知原因")")
                        }.joined(separator:"\n")
                    } else if let note = payload["note"] as? String {
                        self.output = note
                    } else if let devices = payload["devices"] as? String {
                        self.output = devices
                    } else if let result = payload["result"] as? String {
                        self.output = result
                    }
                } else if let data = text.data(using:.utf8), let object = try? JSONSerialization.jsonObject(with:data) as? [String:Any], let error = object["error"] as? String {
                    self.output = error
                }
            }
        }
    }
    func confirmDisable() {
        let names = rows.filter { selection.contains($0.id) && $0.eligible }.map(\.package)
        guard !names.isEmpty else { return }
        let alert = NSAlert(); alert.messageText = "禁用 \(names.count) 个可选组件？"
        alert.informativeText = names.joined(separator:"\n") + "\n\n将记录原始状态，并逐项验证。可以通过恢复按钮撤销。"
        alert.addButton(withTitle:"执行禁用"); alert.addButton(withTitle:"取消")
        if alert.runModal() == .alertFirstButtonReturn { run(["disable","--apply","--packages"] + names) }
    }
    func restore() {
        let alert = NSAlert(); alert.messageText = "恢复本工具禁用的组件？";alert.informativeText="仅处理当前设备及固件的操作记录，其他工具的变更不会被恢复。";alert.addButton(withTitle:"恢复");alert.addButton(withTitle:"取消")
        if alert.runModal() == .alertFirstButtonReturn { run(["restore"]) }
    }
    func install() {
        let p=NSOpenPanel();p.allowedFileTypes=["apk"];p.allowsMultipleSelection=false
        if p.runModal() == .OK, let url=p.url {run(["install",url.path])}
    }
}
struct ContentView: View {
    @StateObject var manager=Manager()
    @State var sourceName=""
    @State var sourceUrl=""
    @State var kind="m3u"
    @State var filter=""
    @State var tab=0
    var body: some View {
        VStack(alignment:.leading,spacing:18) {
            HStack(alignment:.center) {
                Image(systemName:"tv.fill").font(.system(size:32)).foregroundColor(.cyan)
                VStack(alignment:.leading,spacing:4) { Text("客厅管家").font(.title.bold());Text(manager.device).foregroundColor(.secondary) }
                Spacer()
                if manager.busy {ProgressView().scaleEffect(0.7)}
                Text("本地连接 · 无云端账号").font(.caption).foregroundColor(.secondary)
            }
            HStack {
                TextField("电视 IP:端口 或 USB 设备序列号",text:$manager.serial).textFieldStyle(.roundedBorder)
                Button("网络连接"){manager.run(["connect",manager.serial])}
                Button("设备列表"){manager.run(["devices"])}
                Button("扫描"){manager.run(["scan"])}.keyboardShortcut("r")
            }.disabled(manager.busy)
            Picker("功能",selection:$tab){Text("组件管理").tag(0);Text("桌面与安装").tag(1);Text("导入播放源").tag(2)}.pickerStyle(.segmented)
            Group {
                if tab==0 {
                    VStack(alignment:.leading,spacing:10) {
                        Text("只允许选择与实机验证规则匹配的可选组件。未知固件不提供禁用。").font(.callout).foregroundColor(.secondary)
                        TextField("筛选包名",text:$filter).textFieldStyle(.roundedBorder)
                        List {
                            ForEach(manager.rows.filter{filter.isEmpty || $0.package.localizedCaseInsensitiveContains(filter)}) { row in
                                HStack {
                                    Toggle("",isOn:Binding(get:{manager.selection.contains(row.id)},set:{v in if v {manager.selection.insert(row.id)}else{manager.selection.remove(row.id)}})).labelsHidden().disabled(!row.eligible || manager.busy)
                                    VStack(alignment:.leading) {Text(row.package).font(.system(.body,design:.monospaced));Text(row.purpose).font(.caption).foregroundColor(.secondary)}
                                    Spacer()
                                    Text(row.protected ? "受保护" : (row.eligible ? "已验证 · 可选" : "仅扫描")).font(.caption).foregroundColor(row.eligible ? .green : .secondary)
                                }.padding(.vertical,3)
                            }
                        }.frame(minHeight:170)
                        HStack {Button("预览并禁用已选组件"){manager.confirmDisable()}.disabled(manager.selection.isEmpty || manager.busy);Button("恢复本工具的变更"){manager.restore()}.disabled(manager.busy);Spacer();Button("打开操作记录"){NSWorkspace.shared.open(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LivingTV"))}}
                    }
                } else if tab==1 {
                    VStack(alignment:.leading,spacing:18) {
                        Text("先安装 APK，再设置默认桌面。厂商限制及 Home 键行为需逐机型验证。").foregroundColor(.secondary)
                        Button("选择并安装 APK"){manager.install()}
                        Button("将客厅桌面设为默认"){manager.run(["set-home"])}
                        Text("电视内「桌面设置」保留原厂桌面和系统设置入口。\n本工具不禁用原厂桌面。首次连接需要在电视确认调试授权。\n\n网络 ADB 通常需要先在开发者选项开启；若设备使用配对式无线调试，请先通过命令行 adb pair 完成配对，再输入连接端口。\n授权失效后需重新连接，不会永久获得系统权限。").font(.callout).lineSpacing(5)
                        Spacer()
                    }.frame(maxWidth:.infinity,alignment:.leading)
                } else {
                    VStack(alignment:.leading,spacing:14) {
                        Text("地址通过本地 ADB 发送；电视确认保存后才加入源列表。").foregroundColor(.secondary)
                        TextField("源名称",text:$sourceName).textFieldStyle(.roundedBorder)
                        TextField("HTTP / HTTPS 源地址",text:$sourceUrl).textFieldStyle(.roundedBorder)
                        Picker("源类型",selection:$kind){Text("M3U 直播列表").tag("m3u");Text("视频直链 / HLS").tag("direct");Text("苹果CMS接口").tag("cms");Text("TVBox JSON配置").tag("tvbox")}
                        Button("发送到电视"){manager.run(["import-source","--name",sourceName,"--url",sourceUrl,"--kind",kind])}.disabled(sourceName.isEmpty || sourceUrl.isEmpty)
                        Text("TVBox 仅导入 JSON CMS 与直播地址，不执行 JAR、脚本或网页解析器。不要把含私人令牌的源地址分享给他人。").font(.caption).foregroundColor(.secondary)
                        Spacer()
                    }.frame(maxWidth:.infinity,alignment:.leading)
                }
            }.disabled(manager.busy)
            Divider()
            Text("操作结果").font(.headline)
            ScrollView {Text(manager.output).font(.system(size:11,design:.monospaced)).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading)}.frame(height:140).padding(10).background(Color.black.opacity(0.08)).cornerRadius(8)
        }.padding(26).frame(minWidth:850,minHeight:680)
    }
}
@main struct LivingTVManager: App {
    var body: some Scene {WindowGroup {ContentView()}.windowStyle(.titleBar)}
}
