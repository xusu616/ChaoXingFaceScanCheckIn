package io.github.rd227.facescan

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal object Ui {
    val BLUE: Int = Color.rgb(37, 99, 235)
    val INK: Int = Color.rgb(26, 39, 62)
    val MUTED: Int = Color.rgb(100, 116, 139)
    val BACKGROUND: Int = Color.rgb(243, 246, 251)

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
        val padding = dp(context, 18)
        card.setPadding(padding, padding, padding, padding)
        card.setBackground(rounded(context, Color.WHITE, 22))
        val params = LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(context, 14)
        card.setLayoutParams(params)
        return card
    }

    fun button(context: Context, label: String?, primary: Boolean): Button {
        val button = Button(context)
        button.setText(label)
        button.setTextSize(15f)
        button.setAllCaps(false)
        button.setTextColor(if (primary) Color.WHITE else BLUE)
        button.setMinHeight(dp(context, 50))
        button.setBackgroundTintList(null)
        button.setBackground(rounded(context, if (primary) BLUE else Color.rgb(235, 241, 255), 14))
        val params = LinearLayout.LayoutParams(-1, dp(context, 50))
        params.topMargin = dp(context, 10)
        button.setLayoutParams(params)
        return button
    }

    fun edgeInsets(activity: Activity, view: View, horizontalDp: Int, verticalDp: Int) {
        if (Build.VERSION.SDK_INT >= 30) activity.getWindow().setDecorFitsSystemWindows(false)
        activity.getWindow().getDecorView().setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        )
        val horizontal = dp(activity, horizontalDp)
        val vertical = dp(activity, verticalDp)
        view.setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener { target: View?, insets: WindowInsets? ->
            target!!.setPadding(
                horizontal + insets!!.getSystemWindowInsetLeft(),
                vertical + insets.getSystemWindowInsetTop(),
                horizontal + insets.getSystemWindowInsetRight(),
                vertical + insets.getSystemWindowInsetBottom()
            )
            insets
        })
        view.requestApplyInsets()
    }
}
