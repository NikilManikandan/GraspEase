package com.graspease.dev

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.graspease.dev.BuildConfig
import com.graspease.dev.voice.CloudSttConfig
import com.graspease.dev.voice.CloudSttController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class VoiceTesterState(
    val status: String = "Preparing voice tester...",
    val transcription: String = "",
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val modelReady: Boolean = false,
    val error: String? = null,
    val latencyMs: Long? = null,
    val details: List<String> = emptyList(),
)

class VoiceTesterViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(VoiceTesterState())
    val state: StateFlow<VoiceTesterState> = _state

    private val cloudController = CloudSttController()
    private val cloudConfig = CloudSttConfig(
        apiKey = null,
        accessToken = BuildConfig.CLOUD_STT_BEARER.takeIf { it.isNotBlank() },
        tokenEndpoint = BuildConfig.CLOUD_STT_TOKEN_ENDPOINT.takeIf { it.isNotBlank() },
        projectId = "fabled-opus-483704-t6",
        location = "asia-southeast1",
        recognizerId = "default",
        languageCode = "en-US", // en-IN not available for latest_long/asia-southeast1; using en-US
    )

    private var currentJob: Job? = null
    private var streamStartNanos: Long? = null

    init {
        _state.value = _state.value.copy(status = "Cloud STT ready", modelReady = true)
    }

    fun runVoiceTest() {
        if (currentJob?.isActive == true || _state.value.isRecording || _state.value.isTranscribing) return
        currentJob = viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    isRecording = true,
                    isTranscribing = false,
                    status = "Streaming... speak now",
                    transcription = "",
                    error = null,
                    latencyMs = null,
                    details = listOf("Starting cloud stream..."),
                )
                streamStartNanos = System.nanoTime()
                cloudController.startTranscriptStream(
                    scope = this,
                    config = cloudConfig,
                    onTranscript = { text ->
                        val firstLatency = _state.value.latencyMs ?: streamStartNanos?.let {
                            (System.nanoTime() - it) / 1_000_000
                        }
                        _state.value = _state.value.copy(
                            transcription = text,
                            status = "Streaming...",
                            latencyMs = firstLatency ?: _state.value.latencyMs,
                            details = appendDetail(_state.value.details, "Transcript: $text"),
                        )
                    },
                    onStatus = { msg ->
                        _state.value = _state.value.copy(
                            status = msg,
                            details = appendDetail(_state.value.details, msg),
                        )
                    },
                    onError = { err ->
                        _state.value = _state.value.copy(
                            status = "Error: $err",
                            isRecording = false,
                            isTranscribing = false,
                            error = err,
                            details = appendDetail(_state.value.details, "Error: $err"),
                        )
                    },
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    status = "Voice test stopped",
                    isRecording = false,
                    isTranscribing = false,
                    error = null,
                    details = appendDetail(_state.value.details, "Stream cancelled"),
                )
            } catch (e: Exception) {
                Log.w("VoiceTesterViewModel", "Transcription failed", e)
                _state.value = _state.value.copy(
                    status = "Transcription failed: ${e.message ?: e}",
                    isRecording = false,
                    isTranscribing = false,
                    error = e.toString(),
                    details = appendDetail(_state.value.details, "Exception: ${e.message ?: e}"),
                )
            } finally {
                currentJob = null
                streamStartNanos = null
            }
        }
    }

    fun cancelVoiceTest() {
        if (currentJob == null && !_state.value.isRecording && !_state.value.isTranscribing) return
        currentJob?.cancel()
        currentJob = null
        cloudController.stop()
        _state.value = _state.value.copy(
            status = "Voice test stopped",
            isRecording = false,
            isTranscribing = false,
            error = null,
            details = appendDetail(_state.value.details, "Stopped"),
        )
    }

    private fun appendDetail(existing: List<String>, message: String): List<String> {
        val updated = (existing + message).takeLast(20)
        return updated
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VoiceTesterViewModel(app) as T
        }
    }
}
