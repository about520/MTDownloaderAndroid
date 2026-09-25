package com.mtdownloader.app

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object Utils {

    /** 字节数转人类可读 */
    fun fmtBytes(b: Long): String {
        if (b < 0) return "-"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$b B" else String.format("%.2f %s", v, units[i])
    }

    /**
     * 机型 + 屏幕分辨率信息，对应 iOS 版「当前设备」那一栏。
     * getRealMetrics 拿到的是包含系统栏的真实物理分辨率。
     */
    fun deviceInfo(ctx: Context): String {
        val dm = DisplayMetrics()
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels
            "机型：${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "屏幕：${w} × ${h} px　@${dm.densityDpi}dpi（density ${dm.density}）\n" +
                    "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        } catch (e: Exception) {
            "机型：${Build.MANUFACTURER} ${Build.MODEL}\n系统：Android ${Build.VERSION.RELEASE}"
        }
    }
}
