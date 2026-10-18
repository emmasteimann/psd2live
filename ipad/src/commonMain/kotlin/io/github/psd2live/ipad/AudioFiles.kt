package io.github.psd2live.ipad

/** [path] (any audio the system reads: WAV, M4A, MP3, AAC, CAF, AIFF) as mono samples, for lip sync. Blocking. */
expect fun readAudioMono(path: String): io.github.psd2live.core.LipSync.Audio
