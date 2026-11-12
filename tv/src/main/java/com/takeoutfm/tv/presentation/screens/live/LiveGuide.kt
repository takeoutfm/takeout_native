package com.takeoutfm.tv.presentation.screens.live

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "9:00 PM" in the TV's time zone. */
fun Instant.localTime(): String = timeFormat.format(toJavaInstant().atZone(ZoneId.systemDefault()))
