package org.bayton.gmc.ui.components

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private val DATETIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM")

private fun parse(s: String?): LocalDateTime? = s?.take(19)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

fun fmtDate(s: String?): String = parse(s)?.format(DATE) ?: (s?.take(10) ?: "")
fun fmtDateTime(s: String?): String = parse(s)?.format(DATETIME) ?: (s ?: "")
fun fmtTime(s: String?): String = parse(s)?.format(TIME) ?: ""
fun fmtDay(s: String?): String = parse(s)?.format(DAY) ?: (s ?: "")
fun dayKey(s: String?): LocalDate? = parse(s)?.toLocalDate()

fun money(v: Double?, currency: String? = "£"): String =
    if (v == null) "" else (currency ?: "£") + String.format(Locale.UK, "%,.2f", v)
