package com.fedemeju.colortap

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Botón en los ajustes rápidos: activa/pausa la detección. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        Engine.load(this)
        if (!Engine.capturing) {
            openApp()
            return
        }
        Engine.setActive(this, !Engine.active)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = when {
            !Engine.capturing -> Tile.STATE_INACTIVE
            Engine.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                !Engine.capturing -> "Tocá para iniciar"
                Engine.active -> "Activo"
                else -> "En pausa"
            }
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
