import SwiftUI
import AppKit

final class Remote: ObservableObject {
    @Published var status = "按方向键选择，确认键打开"
    private let queue = DispatchQueue(label:"cn.living.tv.remote.commands")
    private var pending=0
    private var monitor:Any?
    private let environment = ProcessInfo.processInfo.environment
    private var adb: String {
        if let path = environment["LIVINGTV_ADB"], !path.isEmpty { return path }
        let sdk = environment["ANDROID_HOME"] ?? environment["ANDROID_SDK_ROOT"]
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Android/sdk").path
        return URL(fileURLWithPath: sdk).appendingPathComponent("platform-tools/adb").path
    }

    private var target: [String] {
        if let serial = environment["LIVINGTV_ADB_SERIAL"], !serial.isEmpty { return ["-s", serial] }
        return ["-e"]
    }
    func send(_ label:String,_ arguments:[String]) {
        guard pending<4 else{return};pending+=1
        queue.async {
            let process=Process();process.executableURL=URL(fileURLWithPath:self.adb)
            process.arguments=self.target+["shell"]+arguments
            let pipe=Pipe();process.standardOutput=pipe;process.standardError=pipe
            var message="已发送："+label
            do {
                try process.run();let data=pipe.fileHandleForReading.readDataToEndOfFile();process.waitUntilExit()
                if process.terminationStatus != 0 {message="发送失败：请先打开电视体验，再重试";let detail=String(data:data,encoding:.utf8) ?? "";NSLog("Remote command failed: %@",detail)}
            }catch{message="无法连接电视模拟器"}
            let final=message
            DispatchQueue.main.async{self.pending-=1;self.status=final}
        }
    }
    func key(_ code:Int,_ label:String){send(label,["input","keyevent",String(code)])}
    func home(){send("回到客厅桌面",["am","start","-n","cn.living.tv/com.gathin.tvhome.MainActivity"])}
    func installKeys(){
        guard monitor==nil else{return}
        monitor=NSEvent.addLocalMonitorForEvents(matching:.keyDown){[weak self] event in
            guard let self=self else{return event}
            if event.modifierFlags.intersection([.command,.control,.option]).isEmpty {
                switch event.keyCode {
                case 123:self.key(21,"向左")
                case 124:self.key(22,"向右")
                case 125:self.key(20,"向下")
                case 126:self.key(19,"向上")
                case 36,76:self.key(23,"确认")
                case 53,51:self.key(4,"返回")
                case 49:self.key(85,"播放 / 暂停")
                default:return event
                }
                return nil
            }
            return event
        }
    }
    deinit{if let monitor=monitor{NSEvent.removeMonitor(monitor)}}
}
struct RemoteView:View {
    @StateObject var remote=Remote()
    func arrow(_ symbol:String,_ name:String,_ code:Int)->some View {
        Button{remote.key(code,name)}label:{Image(systemName:symbol).font(.system(size:23,weight:.semibold)).frame(width:66,height:66).contentShape(Circle())}.buttonStyle(.plain).accessibilityLabel(name).help(name)
    }
    var body:some View {
        VStack(spacing:18) {
            HStack{Image(systemName:"tv.fill").foregroundColor(.cyan);Text("客厅遥控器").font(.title2.bold());Spacer()}
            Text("点击按钮，或先点本窗口再用键盘").font(.caption).foregroundColor(.secondary)
            ZStack {
                Circle().fill(Color.primary.opacity(0.07)).frame(width:234,height:234)
                arrow("chevron.up","向上",19).position(x:120,y:34)
                arrow("chevron.left","向左",21).position(x:34,y:120)
                arrow("chevron.right","向右",22).position(x:206,y:120)
                arrow("chevron.down","向下",20).position(x:120,y:206)
                Button{remote.key(23,"确认")}label:{Text("确认").font(.title3.bold()).foregroundColor(.white).frame(width:90,height:90).background(Color(red:0.09,green:0.20,blue:0.32),in:Circle())}.buttonStyle(.plain).accessibilityLabel("确认").position(x:120,y:120)
            }.frame(width:240,height:240)
            HStack(spacing:12) {
                Button{remote.key(4,"返回")}label:{Label("返回",systemImage:"arrow.uturn.backward").frame(maxWidth:.infinity,minHeight:35)}
                Button{remote.home()}label:{Label("桌面",systemImage:"house").frame(maxWidth:.infinity,minHeight:35)}
            }
            HStack(spacing:12){
                Button{remote.key(85,"播放 / 暂停")}label:{Label("播放 / 暂停",systemImage:"playpause.fill").frame(maxWidth:.infinity,minHeight:30)}
                Button{remote.send("长按确认",["input","keyevent","--longpress","23"])}label:{Text("长按确认").frame(maxWidth:.infinity,minHeight:30)}
            }
            Text(remote.status).font(.callout).frame(maxWidth:.infinity,minHeight:34).padding(8).background(Color.primary.opacity(0.05),in:RoundedRectangle(cornerRadius:8))
            Text("方向键移动 · Enter确认 · Esc返回\n空格播放/暂停；仅控制当前模拟器").font(.caption).foregroundColor(.secondary).multilineTextAlignment(.center)
        }.padding(22).frame(width:300)
        .onAppear{remote.installKeys();NSApp.windows.first?.level = .floating}
    }
}
@main struct LivingTVRemoteApp:App {
    var body:some Scene{WindowGroup("客厅遥控器"){RemoteView()}.windowResizability(.contentSize).defaultPosition(.center)}
}
