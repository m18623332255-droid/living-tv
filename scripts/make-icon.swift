import AppKit
let destination=CommandLine.arguments[1]
let image=NSImage(size:NSSize(width:1024,height:1024))
image.lockFocus()
let bounds=NSRect(x:42,y:42,width:940,height:940)
NSColor(calibratedRed:0.05,green:0.13,blue:0.23,alpha:1).setFill()
NSBezierPath(roundedRect:bounds,xRadius:212,yRadius:212).fill()
NSColor(calibratedRed:0.12,green:0.28,blue:0.43,alpha:1).setFill()
NSBezierPath(roundedRect:NSRect(x:175,y:270,width:674,height:485),xRadius:54,yRadius:54).fill()
NSColor.white.setStroke()
let tv=NSBezierPath(roundedRect:NSRect(x:205,y:300,width:614,height:425),xRadius:32,yRadius:32);tv.lineWidth=26;tv.stroke()
NSColor(calibratedRed:0.56,green:0.84,blue:1,alpha:1).setFill()
let triangle=NSBezierPath();triangle.move(to:NSPoint(x:442,y:392));triangle.line(to:NSPoint(x:442,y:630));triangle.line(to:NSPoint(x:638,y:511));triangle.close();triangle.fill()
let stand=NSBezierPath();stand.move(to:NSPoint(x:512,y:300));stand.line(to:NSPoint(x:512,y:220));stand.move(to:NSPoint(x:395,y:216));stand.line(to:NSPoint(x:629,y:216));stand.lineWidth=26;stand.lineCapStyle = .round;NSColor.white.setStroke();stand.stroke()
image.unlockFocus()
let bitmap=NSBitmapImageRep(data:image.tiffRepresentation!)!
try bitmap.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:destination))
