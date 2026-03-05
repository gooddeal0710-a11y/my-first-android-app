package com.example.neareststationnotifier

import android.content.Context

fun Context.dp(v: Int): Int =
    (v * resources.displayMetrics.density).toInt()
