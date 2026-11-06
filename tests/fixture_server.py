"""Isolated, generated test content; not bundled as a public playback source."""
import http.server
import json
from pathlib import Path
from urllib.parse import urlparse, parse_qs
ROOT=Path(__file__).resolve().parents[1]/'.tools/fixtures'
BASE='http://10.0.2.2:18765'
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self,*a,**kw):super().__init__(*a,directory=str(ROOT),**kw)
    def do_GET(self):
        path=urlparse(self.path).path
        if path=='/cms':
            query=parse_qs(urlparse(self.path).query).get('wd',[''])[0]
            films=[dict(vod_name='本地测试影片',type_name='验收素材',vod_play_from='MP4$$$HLS',vod_play_url='第一集$'+BASE+'/sample.mp4$$$第二集$'+BASE+'/stream.m3u8')]
            return self.respond(json.dumps(dict(list=films if not query or query in '本地测试影片' else []),ensure_ascii=False),'application/json')
        if path=='/tvbox.json':
            return self.respond(json.dumps(dict(sites=[dict(name='本地CMS验收',type=1,api=BASE+'/cms'),dict(name='不支持的脚本',type=3,api='csp_test')],lives=[dict(name='本地直播验收',url=BASE+'/channels.m3u')])),'application/json')
        if path=='/channels.m3u':
            return self.respond('#EXTM3U\n#EXTINF:-1 group-title="验收",MP4测试\n'+BASE+'/sample.mp4\n#EXTINF:-1,HLS测试\n'+BASE+'/stream.m3u8\n','audio/x-mpegurl')
        if path=='/fail':self.send_error(503);return
        return super().do_GET()
    def respond(self,text,mime):
        b=text.encode();self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
if __name__=='__main__':http.server.ThreadingHTTPServer(('127.0.0.1',18765),Handler).serve_forever()
