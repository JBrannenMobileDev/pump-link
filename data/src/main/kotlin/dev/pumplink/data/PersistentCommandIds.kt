package dev.pumplink.data

import dev.pumplink.domain.CommandIdSource
import dev.pumplink.domain.DomainCommandId
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

class PersistentCommandIds(private val file: File) : CommandIdSource {
    private val next: AtomicInteger

    init {
        file.parentFile?.mkdirs()
        next = AtomicInteger(if (file.exists()) file.readText().trim().toInt() else 1)
    }

    override fun next(): DomainCommandId {
        val value = next.getAndIncrement()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(value.inc().toString().toByteArray(Charsets.US_ASCII))
            raf.fd.sync()
        }
        return DomainCommandId(value.toUInt())
    }
}
