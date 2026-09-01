package dev.pumplink.data

import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.Milliunits
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FileJournalTest {

    @Test
    fun entrySurvivesReread() = runTest {
        val file = Files.createTempFile("journal", ".log").toFile()
        file.deleteOnExit()
        val first = FileJournal(file)
        val entry = JournalEntry(
            commandId = DomainCommandId(3u),
            storeInstanceId = DomainStoreId(9uL),
            requested = Dose(Milliunits(500)),
            state = JournalState.InFlight,
            sentAtMillis = 10L,
        )
        first.append(entry)
        val second = FileJournal(file)
        assertEquals(entry, second.snapshot().latest(DomainCommandId(3u)))
    }

    @Test
    fun resolvingRowSurvivesReread() = runTest {
        val file = Files.createTempFile("journal-resolving", ".log").toFile()
        file.deleteOnExit()
        val first = FileJournal(file)
        val command = DomainCommandId(4u)
        first.append(
            JournalEntry(
                commandId = command,
                storeInstanceId = DomainStoreId(9uL),
                requested = Dose(Milliunits(500)),
                state = JournalState.InFlight,
                sentAtMillis = 10L,
            ),
        )
        first.append(
            JournalEntry(
                commandId = command,
                storeInstanceId = DomainStoreId(9uL),
                requested = Dose(Milliunits(500)),
                state = JournalState.Resolving,
                sentAtMillis = 10L,
            ),
        )
        val second = FileJournal(file)
        assertEquals(JournalState.Resolving, second.snapshot().latest(command)?.state)
        assertEquals(true, second.snapshot().wasRecovered(command))
    }
}
