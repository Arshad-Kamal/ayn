package com.arshad.ayn.core.access

import java.security.MessageDigest

/** The bucket [videoId] falls in for a hash-prefix lookup (M7-D2): the first 4 hex digits of its
 *  SHA-256, one of 65,536, so the server never learns the video. */
internal fun hashPrefix(videoId: VideoId): String =
    MessageDigest.getInstance("SHA-256").digest(videoId.value.encodeToByteArray()).copyOf(2).toHexString()
