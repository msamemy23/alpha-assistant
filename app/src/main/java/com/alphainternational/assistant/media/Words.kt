package com.alphainternational.assistant.media

/** One spoken word with its start and end in seconds. */
data class Word(val text: String, val startSec: Float, val endSec: Float)
