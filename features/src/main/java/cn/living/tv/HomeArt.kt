package cn.living.tv

import android.content.Context
import android.graphics.*
import android.view.View

/** Original vector art: no Apple logos or copyrighted wallpaper assets. */
class HomeIconView(context:Context,private val kind:String):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    init{setLayerType(LAYER_TYPE_SOFTWARE,null)}
    private fun gradient(top:Int,bottom:Int,y1:Float=15f,y2:Float=85f){p.shader=LinearGradient(0f,y1,80f,y2,top,bottom,Shader.TileMode.CLAMP)}
    private fun shadow(){p.setShadowLayer(7f,0f,6f,0x70000000)}
    private fun clean(){p.clearShadowLayer();p.shader=null;p.style=Paint.Style.FILL}
    override fun onDraw(c:Canvas){
        val w=width.toFloat();val h=height.toFloat()
        val colors=when(kind){"play"->intArrayOf(0xFF27466F.toInt(),0xFF10192C.toInt());"wall"->intArrayOf(0xFFE9ECF5.toInt(),0xFFADBBD6.toInt());"weather"->intArrayOf(0xFF1976D8.toInt(),0xFF6BBDF1.toInt());"settings"->intArrayOf(0xFFD2D9E4.toInt(),0xFF7D879B.toInt());else->intArrayOf(0xFF758BA8.toInt(),0xFF384966.toInt())}
        p.shader=LinearGradient(0f,0f,w,h,colors,null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p);p.shader=null
        p.color=0x30FFFFFF;c.drawRect(0f,0f,w,h*.025f,p);p.color=0x30000000;c.drawRect(0f,h*.96f,w,h,p)
        c.save();val scale=h/100f;c.translate(w/2-h/2,0f);c.scale(scale,scale)
        when(kind){
            "play"->{
                shadow();p.color=0xFF050A12.toInt();c.drawRoundRect(14f,24f,86f,78f,11f,11f,p);clean()
                gradient(0xFFD7E4F6.toInt(),0xFF7F96B6.toInt());c.drawRoundRect(14f,18f,86f,71f,11f,11f,p);clean()
                gradient(0xFF12213B.toInt(),0xFF253F60.toInt());c.drawRoundRect(19f,23f,81f,65f,7f,7f,p);clean()
                path.reset();path.moveTo(43f,33f);path.lineTo(65f,45f);path.lineTo(43f,58f);path.close()
                c.save();c.translate(0f,3f);p.color=0xFF5F83B4.toInt();c.drawPath(path,p);c.restore()
                gradient(Color.WHITE,0xFFB8D6FF.toInt());c.drawPath(path,p);clean()
                gradient(0xFF9EB4D3.toInt(),0xFF4A6384.toInt(),72f,86f);c.drawRoundRect(44f,72f,56f,82f,2f,2f,p);c.drawRoundRect(33f,80f,67f,85f,3f,3f,p);clean()
            }
            "wall"->{
                val chips=intArrayOf(0xFFE66C88.toInt(),0xFFEBB55D.toInt(),0xFF5DBBA4.toInt(),0xFF559EDE.toInt())
                chips.forEachIndexed{i,color->c.save();c.rotate(-24f+i*13f,50f,60f);shadow();p.color=0x60506182;c.drawRoundRect(27f,21f,73f,82f,7f,7f,p);clean();gradient(Color.WHITE,color);c.drawRoundRect(25f,16f,71f,77f,7f,7f,p);clean();p.color=color;c.drawRoundRect(30f,22f,66f,64f,3f,3f,p);p.color=0xDFFFFFFF.toInt();c.drawCircle(57f,32f,5f,p);path.reset();path.moveTo(30f,59f);path.lineTo(42f,42f);path.lineTo(51f,51f);path.lineTo(57f,45f);path.lineTo(66f,59f);path.close();c.drawPath(path,p);c.restore()}
            }
            "weather"->{
                shadow();p.shader=RadialGradient(57f,24f,31f,intArrayOf(0xFFFFF6B0.toInt(),0xFFFFBD39.toInt(),0xFFE68517.toInt()),floatArrayOf(0f,.7f,1f),Shader.TileMode.CLAMP);c.drawCircle(61f,35f,22f,p);clean()
                path.reset();path.addCircle(31f,56f,16f,Path.Direction.CW);path.addCircle(49f,46f,23f,Path.Direction.CW);path.addCircle(72f,58f,15f,Path.Direction.CW);path.addRoundRect(16f,54f,87f,74f,10f,10f,Path.Direction.CW)
                c.save();c.translate(0f,5f);shadow();p.color=0xFF85A1BE.toInt();c.drawPath(path,p);clean();c.restore()
                gradient(Color.WHITE,0xFFCEDBEA.toInt(),35f,80f);c.drawPath(path,p);clean()
            }
            "settings"->{
                path.reset();for(i in 0 until 96){val a=i*Math.PI/48;val r=if(i%8 in 2..5)33.0 else 28.0;val x=(50+r*Math.cos(a)).toFloat();val y=(48+r*Math.sin(a)).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close()
                c.save();c.translate(0f,6f);shadow();p.color=0xFF3F4B60.toInt();c.drawPath(path,p);clean();c.restore()
                gradient(0xFFF0F3F8.toInt(),0xFF8997AA.toInt());c.drawPath(path,p);clean()
                p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=0xA0FFFFFF.toInt();c.drawPath(path,p);clean()
                gradient(0xFF515E72.toInt(),0xFF2E3A4E.toInt());c.drawCircle(50f,48f,22f,p);clean()
                gradient(0xFFD2DCE9.toInt(),0xFF7F8DA5.toInt());c.drawCircle(50f,49f,17f,p);clean()
                p.color=0xFF46536B.toInt();c.drawCircle(50f,49f,9f,p);p.color=0x508495B0;c.drawArc(41f,40f,59f,58f,0f,180f,false,p)
            }
            else->{shadow();p.color=0xFF33425A.toInt();c.drawRoundRect(18f,28f,82f,79f,9f,9f,p);clean();gradient(0xFFE2E9F6.toInt(),0xFF9DADC4.toInt());c.drawRoundRect(18f,22f,82f,73f,9f,9f,p);clean();for(y in 0..1)for(x in 0..1){gradient(0xFF7591B7.toInt(),0xFF456389.toInt());c.drawRoundRect(28f+x*25,31f+y*20,46f+x*25,46f+y*20,4f,4f,p);clean()}}
        };c.restore();clean()
    }
}
class WeatherGlyph(context:Context):View(context){
    var code=0;var day=true
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val s=minOf(width,height)/100f;c.save();c.scale(s,s);p.style=Paint.Style.FILL
        if(code<=2){p.color=if(day)0xFFFFDC73.toInt() else 0xFFE4EEFF.toInt();c.drawCircle(57f,39f,22f,p);if(!day){p.color=0xFF1C2946.toInt();c.drawCircle(68f,30f,21f,p)}}
        if(code>0){p.color=0xFFE1EAF8.toInt();c.drawCircle(29f,59f,17f,p);c.drawCircle(48f,49f,23f,p);c.drawCircle(71f,61f,17f,p);c.drawRoundRect(14f,58f,85f,77f,12f,12f,p)}
        if(code>=51){p.color=0xFF8BCBFF.toInt();p.strokeWidth=4f;for(x in listOf(31f,49f,67f))c.drawLine(x,83f,x-4,93f,p)}
        c.restore()
    }
}
