package dev.pumplink.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pumplink.domain.CommandJournal
import dev.pumplink.domain.PumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvides {

    @Provides
    @Singleton
    fun commandJournal(@ApplicationContext context: Context): CommandJournal =
        FileJournal(File(context.filesDir, "journal.log"))

    @Provides
    @Singleton
    fun commandIds(@ApplicationContext context: Context): PersistentCommandIds =
        PersistentCommandIds(File(context.filesDir, "command-id.txt"))

    @Provides
    @Singleton
    @SessionCoroutineScope
    fun sessionScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun bleController(
        @ApplicationContext context: Context,
        journal: CommandJournal,
        ids: PersistentCommandIds,
        @SessionCoroutineScope scope: CoroutineScope,
    ): BleController = BleController.demo(context, journal, ids, scope)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {

    @Binds
    @Singleton
    abstract fun pumpRepository(session: PumpSession): PumpRepository
}
