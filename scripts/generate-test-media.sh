#!/bin/zsh
set -eu
cd "${0:A:h:h}"
LIVINGTV_FFMPEG="${LIVINGTV_FFMPEG:-$(command -v ffmpeg || true)}"
if [[ -z "$LIVINGTV_FFMPEG" ]]; then
    LIVINGTV_FFMPEG="$(find .tools/media/imageio_ffmpeg/binaries -type f -name 'ffmpeg-*' -print -quit 2>/dev/null || true)"
fi
[[ -n "$LIVINGTV_FFMPEG" ]] || { echo '请安装 FFmpeg 或设置 LIVINGTV_FFMPEG 为其完整路径'; exit 1; }
mkdir -p .tools/fixtures
"$LIVINGTV_FFMPEG" -y -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 20 -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 48 -c:a aac -b:a 64k -movflags +faststart .tools/fixtures/sample.mp4
"$LIVINGTV_FFMPEG" -y -i .tools/fixtures/sample.mp4 -c copy -hls_time 4 -hls_list_size 0 .tools/fixtures/stream.m3u8
