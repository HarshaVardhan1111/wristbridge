package com.wristbridge.protocol

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
