package com.novastream.tv

import android.content.Context
import java.io.File

/** Android-only factory (kept out of the JVM-testable core). */
fun memoryRepositoryFor(context: Context): MemoryRepository =
    MemoryRepository(File(context.filesDir, "novastream_memory"))
