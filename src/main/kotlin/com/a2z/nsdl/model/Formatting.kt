package com.a2z.nsdl.model

/** Multiplatform-safe lowercase hexadecimal formatting with zero padding. */
fun hex(value: Long, width: Int): String = value.toULong().toString(16).padStart(width, '0').takeLast(width)
