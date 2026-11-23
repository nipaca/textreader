package com.textreader.app.service

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/** The panel's root view. Reports every touch so the 5-second auto-collapse can restart. */
class TouchAwareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onAnyTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onAnyTouch?.invoke()
        return super.dispatchTouchEvent(event)
    }
}
