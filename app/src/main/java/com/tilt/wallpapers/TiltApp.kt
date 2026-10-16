package com.tilt.wallpapers

import android.app.Application
import com.tilt.wallpapers.data.Repo

class TiltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        com.tilt.wallpapers.tile.Shortcuts.start(this)
    }
}
