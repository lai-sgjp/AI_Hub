package cn.aitavern.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.max

object AvatarThumbnail {
    fun create(encoded: String): String {
        if(encoded.isBlank()) return encoded
        val bytes=Base64.decode(encoded,Base64.DEFAULT)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "角色头像无法解码" }
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>512) sample*=2
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
            BitmapFactory.Options().apply { inSampleSize=sample })) { "角色头像无法解码" }
        val ratio=minOf(1.0,256.0/max(bitmap.width,bitmap.height))
        val thumbnail=bitmap.scale(max(1,(bitmap.width*ratio).toInt()),max(1,(bitmap.height*ratio).toInt()))
        return try {
            ByteArrayOutputStream().use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.PNG,100,output))
                Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
            }
        } finally {
            if(thumbnail!==bitmap) thumbnail.recycle()
            bitmap.recycle()
        }
    }
}
