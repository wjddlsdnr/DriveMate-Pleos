package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Records simulated commands; no media service, account, network or audio output. */
class FakeMediaController : MediaController {
    var failure: FailureReason? = null
    private val mutableLastCommand = MutableStateFlow<MediaCommand?>(null)
    val lastCommand = mutableLastCommand.asStateFlow()
    override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
        failure?.let { return OperationResult.Failure(it) }
        mutableLastCommand.value = command
        return OperationResult.Success(Unit)
    }
}
