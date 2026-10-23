package io.github.rd227.facescan

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal object Ui {
    val BLUE: Int = Color.rgb(46, 87, 209)
    val INK: Int = Color.rgb(27, 36, 55)
    val MUTED: Int = Color.rgb(112, 122, 143)
    val BACKGROUND: Int = Color.rgb(242, 244, 248)

    fun dp(context: Context, value: Int): Int {
        return Math.round(value * context.getResources().getDisplayMetrics().density)
    }

    fun text(context: Context, value: String?, size: Int, color: Int, bold: Boolean): TextView {
        val view = TextView(context)
        view.setText(value)
        view.setTextSize(size.toFloat())
        view.setTextColor(color)
        view.setLineSpacing(dp(context, 3).toFloat(), 1f)
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        view.setPadding(0, dp(context, 4), 0, dp(context, 4))
        return view
    }

    fun rounded(context: Context, color: Int, radius: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.setCornerRadius(dp(context, radius).toFloat())
        return shape
    }

    fun card(context: Context): LinearLayout {
        val card = LinearLayout(context)
        card.setOrientation(LinearLayout.VERTICAL)
        val padding = dp(context, 20)
        card.setPadding(padding, padding, padding, padding)
        card.setBackground(rounded(context, Color.WHITE, 20))
        val params = LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(context, 14)
        card.setLayoutParams(params)
        return card
    }

    fun button(context: Context, label: String?, primary: Boolean): Button {
        val button = Button(context)
        button.stateListAnimator = null
        button.elevation = 0f
        button.setText(label)
        button.setTextSize(14f)
        button.setAllCaps(false)
        val colors = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, if (primary) Color.WHITE else BLUE))
        button.setTextColor(colors)
        button.compoundDrawableTintList = colors
        button.setMinHeight(dp(context, 48))
        button.minimumWidth = 0
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0)
        button.setBackgroundTintList(null)
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(context, BACKGROUND, 12))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, if (primary) 0xFF2345AD.toInt() else 0xFFDCE5FB.toInt(), 12))
            addState(intArrayOf(), rounded(context, if (primary) BLUE else 0xFFEEF2FC.toInt(), 12))
        }
        val params = LinearLayout.LayoutParams(-2, dp(context, 48))
        params.topMargin = dp(context, 12)
        button.setLayoutParams(params)
        return button
    }

    fun actions(context: Context, vararg buttons: Button): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(0, dp(context, 12), 0, 0)
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                    if (index > 0) marginStart = dp(context, 10)
                })
            }
        }
    }

    fun icon(button: Button, resource: Int) {
        val drawable = button.context.getDrawable(resource)!!
        val size = dp(button.context, 18)
        drawable.setBounds(0, 0, size, size)
        button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(button.context, 6)
    }

    fun edgeInsets(activity: Activity, view: View, horizontalDp: Int, verticalDp: Int) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        val horizontal = dp(activity, horizontalDp)
        val vertical = dp(activity, verticalDp)
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime()
            )
            target.setPadding(
                horizontal + safeInsets.left,
                vertical + safeInsets.top,
                horizontal + safeInsets.right,
                vertical + safeInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
