/*
 * SPDX-FileCopyrightText: 2019 CypherOS
 * SPDX-FileCopyrightText: 2014-2020 Paranoid Android
 * SPDX-FileCopyrightText: 2023-2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.animation.Animator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AlertSliderDialog(private val context: Context) :
    Dialog(context, R.style.alert_slider_theme) {
    private val dialogView by lazy { findViewById<LinearLayout>(R.id.alert_slider_dialog)!! }
    private val frameView by lazy { findViewById<ViewGroup>(R.id.alert_slider_view)!! }
    private val iconView by lazy { findViewById<ImageView>(R.id.alert_slider_icon)!! }
    private val textView by lazy { findViewById<TextView>(R.id.alert_slider_text)!! }

    private val rotation: Int = context.getDisplay().getRotation()
    private val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    private val flip = context.resources.getBoolean(R.bool.alert_slider_dialog_left)

    private val length: Int
    private val xPos: Int
    private val yPos: Int

    private var isAnimating = false
    private var animator = ValueAnimator()

    init {
        window?.let {
            it.requestFeature(Window.FEATURE_NO_TITLE)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            it.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            it.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            it.attributes =
                it.attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = TAG
                }
        }

        setCanceledOnTouchOutside(false)
        setContentView(R.layout.alert_slider_dialog)

        // position calculations
        val res = context.resources
        val fraction = res.getFraction(R.fraction.alert_slider_dialog_y, 1, 1)
        val widthPixels = res.displayMetrics.widthPixels
        val heightPixels = res.displayMetrics.heightPixels
        val pads = dialogView.paddingTop * 2 // equal paddings in all 4 directions
        length =
            if (isLandscape) res.getDimension(R.dimen.alert_slider_dialog_width).toInt()
            else res.getDimension(R.dimen.alert_slider_dialog_height).toInt()
        val hv = (length + pads) * 0.5

        xPos =
            if (isLandscape) (widthPixels * fraction - hv).toInt()
            else if (flip) 0 else widthPixels / 100
        yPos =
            if (isLandscape) (if (flip) (widthPixels / 100) else 0)
            else (heightPixels * fraction - hv).toInt()

        window?.let {
            it.attributes =
                it.attributes.apply {
                    gravity =
                        when (rotation) {
                            Surface.ROTATION_0 ->
                                if (flip) Gravity.TOP or Gravity.LEFT
                                else Gravity.TOP or Gravity.RIGHT
                            Surface.ROTATION_90 ->
                                if (flip) Gravity.BOTTOM or Gravity.LEFT
                                else Gravity.TOP or Gravity.LEFT
                            Surface.ROTATION_270 ->
                                if (flip) Gravity.TOP or Gravity.RIGHT
                                else Gravity.BOTTOM or Gravity.RIGHT
                            else ->
                                if (flip) Gravity.BOTTOM or Gravity.LEFT
                                else Gravity.TOP or Gravity.LEFT
                        }

                    x = xPos
                    y = yPos
                }
        }
    }

    @Synchronized
    fun setState(position: Int, ringerMode: Int, packageName: String? = null) {
        val delta =
            length *
                when (position) {
                    KeyHandler.POSITION_TOP -> -1
                    KeyHandler.POSITION_BOTTOM -> 1
                    else -> 0 // KeyHandler.POSITION_MIDDLE
                }

        var endX = xPos
        var endY = yPos
        if (isLandscape) endX += delta else endY += delta

        if (isShowing) {
            animatePosition(endX, endY, position, ringerMode, packageName)
        } else {
            applyUiMode(ringerMode, packageName)
            applyPositionAndBackground(endX, endY, position)
        }
    }

    @Synchronized
    private fun animatePosition(
        endX: Int,
        endY: Int,
        position: Int,
        ringerMode: Int,
        packageName: String? = null,
    ) {
        if (isAnimating) animator.cancel()
        animator = ValueAnimator()
        animator.duration = 100
        animator.interpolator = OvershootInterpolator()

        window?.let {
            animator.setValues(
                PropertyValuesHolder.ofInt("x", it.attributes.x, endX),
                PropertyValuesHolder.ofInt("y", it.attributes.y, endY),
            )
        }

        animator.addUpdateListener { animation ->
            window?.let {
                it.attributes =
                    it.attributes.apply {
                        x = animation.getAnimatedValue("x") as Int
                        y = animation.getAnimatedValue("y") as Int
                    }
            }
        }

        animator.addListener(
            object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    isAnimating = true
                    applyUiMode(ringerMode, packageName)
                    val transition =
                        TransitionDrawable(
                            arrayOf(
                                frameView.background,
                                context.resources.getDrawable(
                                    backgroundFor(rotation, position, flip),
                                    null,
                                ),
                            )
                        )
                    frameView.background = transition
                    transition.setCrossFadeEnabled(true)
                    transition.startTransition(30)
                }

                override fun onAnimationEnd(animation: Animator) {
                    applyPositionAndBackground(endX, endY, position)
                    isAnimating = false
                }

                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationRepeat(animation: Animator) {}
            }
        )
        animator.start()
    }

    private fun applyUiMode(ringerMode: Int, packageName: String? = null) {
        when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT, MODE_SILENT -> {
                iconView.setImageResource(R.drawable.ic_volume_ringer_mute)
                textView.setText(R.string.alert_slider_mode_silent)
            }
            AudioManager.RINGER_MODE_VIBRATE, MODE_VIBRATE -> {
                iconView.setImageResource(R.drawable.ic_volume_ringer_vibrate)
                textView.setText(R.string.alert_slider_mode_vibration)
            }
            AudioManager.RINGER_MODE_NORMAL, MODE_RING, MODE_NONE -> {
                iconView.setImageResource(R.drawable.ic_volume_ringer)
                textView.setText(R.string.alert_slider_mode_normal)
            }
            KeyHandler.ZEN_PRIORITY_ONLY, MODE_PRIORITY_ONLY -> {
                iconView.setImageResource(R.drawable.ic_notifications_alert)
                textView.setText(R.string.alert_slider_mode_dnd_priority_only)
            }
            KeyHandler.ZEN_TOTAL_SILENCE, MODE_TOTAL_SILENCE -> {
                iconView.setImageResource(R.drawable.ic_notifications_silence)
                textView.setText(R.string.alert_slider_mode_dnd_total_silence)
            }
            KeyHandler.ZEN_ALARMS_ONLY, MODE_ALARMS_ONLY -> {
                iconView.setImageResource(R.drawable.ic_alarm)
                textView.setText(R.string.alert_slider_mode_dnd_alarms_only)
            }
            KeyHandler.TORCH_ON, MODE_FLASHLIGHT_ON -> {
                iconView.setImageResource(R.drawable.ic_torch_on)
                textView.setText(R.string.alert_slider_mode_torch_on)
            }
            KeyHandler.TORCH_OFF, MODE_FLASHLIGHT_OFF -> {
                iconView.setImageResource(R.drawable.ic_torch_off)
                textView.setText(R.string.alert_slider_mode_torch_off)
            }
            MODE_FLASHLIGHT_BLINK -> {
                iconView.setImageResource(R.drawable.ic_torch_on)
                textView.setText(R.string.alert_slider_mode_torch_blink)
            }
            MODE_BRIGHTNESS_AUTO -> {
                iconView.setImageResource(R.drawable.ic_brightness_auto)
                textView.setText(R.string.alert_slider_mode_brightness_auto)
            }
            MODE_BRIGHTNESS_BRIGHT -> {
                iconView.setImageResource(R.drawable.ic_brightness)
                textView.setText(R.string.alert_slider_mode_brightness_brightest)
            }
            MODE_BRIGHTNESS_DARK -> {
                iconView.setImageResource(R.drawable.ic_brightness)
                textView.setText(R.string.alert_slider_mode_brightness_darkest)
            }
            MODE_ROTATION_AUTO -> {
                iconView.setImageResource(R.drawable.ic_rotate)
                textView.setText(R.string.alert_slider_mode_rotation_auto)
            }
            MODE_ROTATION_0 -> {
                iconView.setImageResource(R.drawable.ic_rotate)
                textView.setText(R.string.alert_slider_mode_rotation_portrait)
            }
            MODE_ROTATION_90 -> {
                iconView.setImageResource(R.drawable.ic_rotate)
                textView.setText(R.string.alert_slider_mode_rotation_landscape_90)
            }
            MODE_ROTATION_270 -> {
                iconView.setImageResource(R.drawable.ic_rotate)
                textView.setText(R.string.alert_slider_mode_rotation_landscape_270)
            }
            MODE_APP_LAUNCH -> {
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val pm = context.packageManager
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        iconView.setImageDrawable(pm.getApplicationIcon(appInfo))
                        textView.text = pm.getApplicationLabel(appInfo)
                    } catch (e: Exception) {
                        iconView.setImageResource(R.drawable.ic_info)
                        textView.text = packageName
                    }
                } else {
                    iconView.setImageResource(R.drawable.ic_info)
                    textView.setText(R.string.alert_slider_mode_app_none)
                }
            }
            else -> {
                iconView.setImageResource(R.drawable.ic_info)
                textView.setText(R.string.alert_slider_mode_none)
            }
        }
        textView.setTextColor(context.getColor(R.color.alert_slider_text_color))
    }

    private fun applyPositionAndBackground(endX: Int, endY: Int, position: Int) {
        window?.let {
            it.attributes =
                it.attributes.apply {
                    x = endX
                    y = endY
                }
        }
        frameView.setBackgroundResource(backgroundFor(rotation, position, flip))
    }

    private fun backgroundFor(rotation: Int, position: Int, flip: Boolean): Int {
        fun base(position: Int): Int =
            when (position) {
                KeyHandler.POSITION_TOP ->
                    if (flip) R.drawable.alert_slider_top_flip else R.drawable.alert_slider_top
                KeyHandler.POSITION_MIDDLE -> R.drawable.alert_slider_middle
                KeyHandler.POSITION_BOTTOM ->
                    if (flip) R.drawable.alert_slider_bottom_flip
                    else R.drawable.alert_slider_bottom
                else -> R.drawable.alert_slider_middle
            }

        return when (rotation) {
            Surface.ROTATION_90 ->
                when (position) {
                    KeyHandler.POSITION_TOP ->
                        if (flip) R.drawable.alert_slider_top_90_flip
                        else R.drawable.alert_slider_top_90
                    KeyHandler.POSITION_BOTTOM ->
                        if (flip) R.drawable.alert_slider_bottom_90_flip
                        else R.drawable.alert_slider_bottom_90
                    else -> R.drawable.alert_slider_middle
                }
            Surface.ROTATION_270 ->
                when (position) {
                    KeyHandler.POSITION_TOP ->
                        if (flip) R.drawable.alert_slider_top_270_flip
                        else R.drawable.alert_slider_top_270
                    KeyHandler.POSITION_BOTTOM ->
                        if (flip) R.drawable.alert_slider_bottom_270_flip
                        else R.drawable.alert_slider_bottom_270
                    else -> R.drawable.alert_slider_middle
                }
            else -> base(position) // ROTATION_0 / ROTATION_180
        }
    }

    override fun show() {
        dialogView.alpha = 0f
        super.show()
        dialogView
            .animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun dismiss() {
        dialogView
            .animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.dismiss() }
            .start()
    }

    companion object {
        private const val TAG = "AlertSliderDialog"

        // DeviceSettings slider action constants
        const val MODE_TOTAL_SILENCE = 600
        const val MODE_ALARMS_ONLY = 601
        const val MODE_PRIORITY_ONLY = 602
        const val MODE_NONE = 603
        const val MODE_VIBRATE = 604
        const val MODE_RING = 605
        const val MODE_SILENT = 620
        const val MODE_FLASHLIGHT_ON = 621
        const val MODE_FLASHLIGHT_OFF = 622
        const val MODE_FLASHLIGHT_BLINK = 623
        const val MODE_BRIGHTNESS_BRIGHT = 630
        const val MODE_BRIGHTNESS_DARK = 631
        const val MODE_BRIGHTNESS_AUTO = 632
        const val MODE_ROTATION_AUTO = 640
        const val MODE_ROTATION_0 = 641
        const val MODE_ROTATION_90 = 642
        const val MODE_ROTATION_270 = 643
        const val MODE_APP_LAUNCH = 650
    }
}
