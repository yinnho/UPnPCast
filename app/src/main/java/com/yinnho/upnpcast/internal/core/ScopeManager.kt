package com.yinnho.upnpcast.internal.core

import kotlinx.coroutines.*

/**
 * Unified coroutine scope management
 *
 * cleanup() cancels the current scopes and recreates fresh ones so that a
 * subsequent init() cycle works without restarting the process.
 */
internal object ScopeManager {

    @Volatile
    private var _appScope: CoroutineScope = createAppScope()

    @Volatile
    private var _uiScope: CoroutineScope = createUiScope()

    val appScope: CoroutineScope get() = _appScope
    val uiScope: CoroutineScope get() = _uiScope

    private fun createAppScope() =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("UPnPCast"))

    private fun createUiScope() =
        CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineName("UPnPCast-UI"))

    fun cleanup() {
        _appScope.cancel()
        _uiScope.cancel()
        _appScope = createAppScope()
        _uiScope = createUiScope()
    }
}
