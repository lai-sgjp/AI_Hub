package cn.aitavern.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AvatarThumbnailDeviceTest {
    @Test fun importedAvatarIsBoundedAndEmptyAvatarIsPreserved() {
        val source=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG,100,output)
            output.toByteArray()
        }
        source.recycle()
        val encoded=AvatarThumbnail.create(Base64.encodeToString(bytes,Base64.NO_WRAP))
        val thumbnail=Base64.decode(encoded,Base64.DEFAULT)
        val bitmap=BitmapFactory.decodeByteArray(thumbnail,0,thumbnail.size)
        assertTrue(bitmap.width<=256 && bitmap.height<=256)
        assertEquals(1.5,bitmap.width.toDouble()/bitmap.height,0.02)
        bitmap.recycle()
        assertEquals("",AvatarThumbnail.create(""))
    }
}
