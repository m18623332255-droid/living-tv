#!/bin/zsh
set -eu
source "${0:A:h}/env.sh"
cd "$LIVINGTV_ROOT"
APP="delivery/客厅管家.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/bin"
xcrun swiftc mac/Manager.swift -parse-as-library -O -target arm64-apple-macosx13.0 -o "$APP/Contents/MacOS/LivingTVManager"
cp mac/Manager.icns mac/livingtv.py mac/rules.json "$APP/Contents/Resources/"
cp "$ANDROID_HOME/platform-tools/adb" "$APP/Contents/Resources/bin/"
cp "$ANDROID_HOME/platform-tools/NOTICE.txt" "$APP/Contents/Resources/bin/ADB-NOTICE.txt"
if [[ -d "$ANDROID_HOME/platform-tools/lib64" ]];then cp -R "$ANDROID_HOME/platform-tools/lib64" "$APP/Contents/Resources/bin/";fi
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleExecutable</key><string>LivingTVManager</string><key>CFBundleIdentifier</key><string>cn.living.tv.manager</string><key>CFBundleName</key><string>客厅管家</string><key>CFBundleIconFile</key><string>Manager.icns</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1.0.0</string><key>CFBundlePackageType</key><string>APPL</string><key>LSMinimumSystemVersion</key><string>13.0</string><key>NSHighResolutionCapable</key><true/></dict></plist>
PLIST
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"
