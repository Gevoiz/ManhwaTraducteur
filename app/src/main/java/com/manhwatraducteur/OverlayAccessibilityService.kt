package com.manhwatraducteur

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Service d'accessibilité qui ne sert qu'à une chose : fournir des fenêtres de type
 * TYPE_ACCESSIBILITY_OVERLAY. Android les considère comme fiables, donc elles laissent passer
 * les touchers même quand elles sont 100 % opaques (contrairement aux superpositions classiques
 * depuis Android 12). On peut ainsi faire défiler la page en glissant sur une traduction.
 *
 * Il ne lit pas le contenu de l'écran et n'effectue aucune action.
 */
class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: OverlayAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, OverlayAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
