package com.kscanner.core.io

import java.io.File

/**
 * Identity of the bytes at [path] for a decode cache. Changes when the file behind an
 * unchanged path is rewritten; equal [File.length] and [File.lastModified] with a
 * different [File.absolutePath] must not collide, hence the path is part of the key.
 *
 * A null key means "nothing to decode", so a blank path never produces a cached image.
 *
 * Stated limit: length plus mtime is not a content hash. A rewrite inside the same
 * timestamp tick with an identical length is invisible here, which is why every path that
 * rewrites a page in place also passes a `revision` that moves in the database.
 */
internal fun imageStateKey(path: String?): Any? =
    path?.takeIf { it.isNotBlank() }?.let { p ->
        val f = File(p)
        Triple(f.absolutePath, f.length(), f.lastModified())
    }