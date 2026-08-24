package com.animeav1.data

/**
 * Payloads captured from the live site (and from a real MP4Upload embed) and frozen under
 * `src/test/resources`. Real captures, not hand-written samples: the bugs these tests guard
 * against were all cases where the real thing differed from what the parser assumed.
 */
internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "falta el fixture $name" }
        .bufferedReader().use { it.readText() }
