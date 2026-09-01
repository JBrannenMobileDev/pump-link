package dev.pumplink.data

import dev.pumplink.domain.AbortReason
import dev.pumplink.domain.CommandJournal
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.Resolution
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only journal. Each record is length-prefixed. Success is not
 * returned until `FileDescriptor.sync()` returns. See ADR-06.
 */
class FileJournal(private val file: File) : CommandJournal {
    private val lock = ReentrantLock()
    private val entries = mutableListOf<JournalEntry>()

    init {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            entries += readAll()
        }
    }

    override suspend fun append(entry: JournalEntry) {
        lock.withLock {
            val encoded = encode(entry)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write(encoded)
                raf.fd.sync()
            }
            entries += entry
        }
    }

    override fun snapshot(): JournalSnapshot = lock.withLock { JournalSnapshot(entries.toList()) }

    private fun readAll(): List<JournalEntry> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val loaded = ArrayList<JournalEntry>()
        while (buffer.remaining() >= 2) {
            val length = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < length) break
            val record = ByteArray(length)
            buffer.get(record)
            loaded += decode(record)
        }
        return loaded
    }

    companion object {
        private fun encode(entry: JournalEntry): ByteArray {
            val body = buildString {
                append(entry.commandId.value)
                append('|')
                append(entry.storeInstanceId.value)
                append('|')
                append(entry.requested.milliunits.value)
                append('|')
                append(entry.state.name)
                append('|')
                append(entry.sentAtMillis)
                append('|')
                append(entry.delivered?.milliunits?.value ?: -1)
                append('|')
                append(encodeResolution(entry.resolution))
            }.toByteArray(Charsets.US_ASCII)
            val header = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(body.size.toShort()).array()
            return header + body
        }

        private fun decode(body: ByteArray): JournalEntry {
            val parts = body.toString(Charsets.US_ASCII).split('|')
            require(parts.size >= 7)
            val delivered = parts[5].toInt()
            return JournalEntry(
                commandId = DomainCommandId(parts[0].toUInt()),
                storeInstanceId = DomainStoreId(parts[1].toULong()),
                requested = Dose(Milliunits(parts[2].toInt())),
                state = JournalState.valueOf(parts[3]),
                sentAtMillis = parts[4].toLong(),
                delivered = if (delivered < 0) null else Dose(Milliunits(delivered)),
                resolution = decodeResolution(parts[6]),
            )
        }

        private fun encodeResolution(resolution: Resolution?): String = when (resolution) {
            null -> ""
            Resolution.NeverSeen -> "NeverSeen"
            Resolution.InFlight -> "InFlight"
            is Resolution.Completed -> "Completed:${resolution.delivered.milliunits.value}"
            is Resolution.Aborted -> "Aborted:${resolution.delivered.milliunits.value}:${resolution.reason.name}"
            Resolution.Indeterminate -> "Indeterminate"
        }

        private fun decodeResolution(text: String): Resolution? {
            if (text.isEmpty()) return null
            val parts = text.split(':')
            return when (parts[0]) {
                "NeverSeen" -> Resolution.NeverSeen
                "InFlight" -> Resolution.InFlight
                "Completed" -> Resolution.Completed(Dose(Milliunits(parts[1].toInt())))
                "Aborted" -> Resolution.Aborted(Dose(Milliunits(parts[1].toInt())), AbortReason.valueOf(parts[2]))
                "Indeterminate" -> Resolution.Indeterminate
                else -> null
            }
        }
    }
}
