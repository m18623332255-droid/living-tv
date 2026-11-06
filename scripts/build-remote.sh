#!/bin/zsh
set -eu
cd "${0:A:h:h}"
APP="delivery/客厅遥控器.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
xcrun swiftc mac/Remote.swift -parse-as-library -O -target arm64-apple-macosx13.0 -o "$APP/Contents/MacOS/LivingTVRemote"
cp mac/Manager.icns "$APP/Contents/Resources/Manager.icns"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleExecutable</key><string>LivingTVRemote</string><key>CFBundleIdentifier</key><string>cn.living.tv.remote</string><key>CFBundleName</key><string>客厅遥控器</string><key>CFBundleVersion</key><string>1</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleIconFile</key><string>Manager.icns</string><key>LSMinimumSystemVersion</key><string>13.0</string><key>NSHighResolutionCapable</key><true/></dict></plist>
PLIST
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"
