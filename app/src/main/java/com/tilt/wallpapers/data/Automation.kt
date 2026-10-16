package com.tilt.wallpapers.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.PowerManager
import java.util.Calendar

/**
 * Time-based behaviour without any permission: the live wallpaper re-checks the schedule and night mode
 * whenever it becomes visible and at each boundary while shown, so changes land on the minute exactly
 * when someone can see them. WorkManager only keeps the app's state current in the background.
 */
object Automation {

    data class Slot(val schedule: Schedule, val key: String)

    private fun nowMinute(cal: Calendar = Calendar.getInstance()) = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    /** The schedule entry in force now and its identity (which day's occurrence it is). */
    fun activeSlot(schedules: List<Schedule>, cal: Calendar = Calendar.getInstance()): Slot? {
        val on = schedules.filter { it.enabled }.sortedBy { it.minuteOfDay }
        if (on.isEmpty()) return null
        val now = nowMinute(cal)
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val today = on.lastOrNull { it.minuteOfDay <= now }
        return if (today != null) Slot(today, "$day:${today.minuteOfDay}")
        else on.last().let { Slot(it, "${day - 1}:${it.minuteOfDay}") }   // still yesterday's last slot
    }

    /** The next entry to take over and how many minutes until then. */
    fun next(schedules: List<Schedule>, cal: Calendar = Calendar.getInstance()): Pair<Schedule, Int>? {
        val on = schedules.filter { it.enabled }.sortedBy { it.minuteOfDay }
        if (on.isEmpty()) return null
        val now = nowMinute(cal)
        val n = on.firstOrNull { it.minuteOfDay > now } ?: on.firstOrNull() ?: return null
        return n to ((n.minuteOfDay - now + 1440) % 1440).let { if (it == 0) 1440 else it }
    }

    /** Milliseconds until the next whole minute, where every schedule and night boundary falls. */
    fun msToNextMinute(): Long {
        val cal = Calendar.getInstance()
        val secLeft = 60 - cal.get(Calendar.SECOND)
        return secLeft * 1000L - cal.get(Calendar.MILLISECOND) + 50
    }

    fun inNightHours(s: Settings, cal: Calendar = Calendar.getInstance()): Boolean {
        val now = nowMinute(cal)
        return if (s.nightStart <= s.nightEnd) now in s.nightStart until s.nightEnd
        else now >= s.nightStart || now < s.nightEnd
    }

    /** How much to dim the wallpaper now (0 = not at all). */
    fun nightDim(context: Context, s: Settings): Float = when (s.night) {
        NightMode.OFF -> 0f
        NightMode.SYSTEM_DARK -> if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) s.nightDim else 0f
        NightMode.HOURS -> if (inNightHours(s)) s.nightDim else 0f
    }

    /** True when the system has animations removed ("Xóa hiệu ứng động"). */
    fun reduceMotion(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Battery saver of the system, or battery under the user's threshold while not charging. */
    fun lowPower(context: Context, s: Settings): Boolean {
        if (!s.batterySaver) return false
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return true
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || plugged) return false
        return level * 100 / scale.coerceAtLeast(1) <= s.batteryThreshold
    }

    fun fmt(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
