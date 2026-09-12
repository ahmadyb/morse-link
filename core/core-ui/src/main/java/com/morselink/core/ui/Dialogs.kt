package com.morselink.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * §10 — every denied permission must end in a recovery path, never a dead end.
 */
object Dialogs {

    fun permissionRationale(
        context: Context,
        title: String,
        message: String,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ -> openAppSettings(context) }
            .show()
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String = "OK",
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .show()
    }

    fun input(
        context: Context,
        title: String,
        initial: String = "",
        hint: String = "",
        onResult: (String) -> Unit,
    ) {
        val input = EditText(context).apply {
            setText(initial)
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ -> onResult(input.text.toString().trim()) }
            .show()
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
