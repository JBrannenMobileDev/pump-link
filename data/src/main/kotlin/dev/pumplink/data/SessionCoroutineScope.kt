package dev.pumplink.data

import javax.inject.Qualifier

/** Process-scoped session work. Not [androidx.lifecycle.viewModelScope]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SessionCoroutineScope
