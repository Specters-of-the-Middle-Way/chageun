package kr.drivestep.tutor.branding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LauncherIconTest {
    @Test fun launcherRoundAndThemedAssetsLoadAndRenderThroughAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val normal = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(normal is AdaptiveIconDrawable)
        val icon = normal as AdaptiveIconDrawable
        assertNotNull(icon.foreground); assertNotNull(icon.background); assertNotNull(icon.monochrome)
        val directory = File(context.filesDir,"verification-icons").apply { mkdirs() }
        fun render(drawable: Drawable, name: String, size: Int) {
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,size,size);drawable.draw(Canvas(bitmap))
            assertTrue(Color.alpha(bitmap.getPixel(size/2,size/2))>0)
            assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
            File(directory,name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
        }
        render(icon,"launcher-512.png",512)
        render(icon,"launcher-48.png",48)
        render(context.getDrawable(R.mipmap.ic_launcher_round)!!,"launcher-round.png",192)
        val themed=AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(0xffd9eee7.toInt()),
            icon.monochrome!!.mutate().apply { setTint(0xff123e37.toInt()) })
        render(themed,"launcher-themed.png",192)
    }
}
