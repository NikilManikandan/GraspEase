package com.graspease.dev.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.google.cloud.speech.v2.ExplicitDecodingConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.RecognitionFeatures
import com.google.cloud.speech.v2.RecognizeRequest
import com.google.cloud.speech.v2.RecognizeResponse
import com.google.cloud.speech.v2.SpeechGrpc
import com.google.cloud.speech.v2.StreamingRecognitionConfig
import com.google.cloud.speech.v2.StreamingRecognitionFeatures
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import io.grpc.CallOptions
import io.grpc.ClientInterceptor
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Metadata.ASCII_STRING_MARSHALLER
import io.grpc.stub.StreamObserver
import io.grpc.okhttp.OkHttpChannelBuilder

data class CloudSttConfig(
    val apiKey: String? = null,
    val accessToken: String? = null,
    val tokenEndpoint: String? = null,
    val projectId: String,
    val location: String = "asia-southeast1",
    val recognizerId: String = "default",
    val languageCode: String = "en-US",
    val model: String = "chirp",
)

class CloudSttController {
    private var job: Job? = null
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .build()
    @Volatile private var cachedBearer: String? = null
    private var channel: ManagedChannel? = null
    private var asyncStub: SpeechGrpc.SpeechStub? = null
    private var blockingStub: SpeechGrpc.SpeechBlockingStub? = null

    fun startTranscriptStream(
        scope: CoroutineScope,
        config: CloudSttConfig,
        onTranscript: (String) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                ensureBearer(config)?.let { cachedBearer = it } ?: run {
                    onError("Missing bearer token")
                    return@launch
                }
                ensureStubs(config)
                onStatus("Starting REST streaming...")
                streamTranscripts(config, onTranscript, onStatus, onError)
            } catch (e: Exception) {
                Log.w("CloudSttController", "Transcript stream error", e)
                onError(e.localizedMessage ?: "Cloud STT error")
            } finally {
                stop()
            }
        }
    }

    fun start(
        scope: CoroutineScope,
        config: CloudSttConfig,
        onCommand: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                ensureBearer(config)?.let { cachedBearer = it } ?: run {
                    onError("Missing bearer token")
                    return@launch
                }
                ensureStubs(config)
                onStatus("Starting REST streaming...")
                listenAndStream(config, onCommand, onStatus, onError)
            } catch (e: Exception) {
                Log.w("CloudSttController", "Voice loop error", e)
                onError(e.localizedMessage ?: "Cloud STT error")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            channel?.shutdownNow()
        } catch (_: Exception) {
        }
        channel = null
        asyncStub = null
        blockingStub = null
    }

    private suspend fun streamTranscripts(
        config: CloudSttConfig,
        onTranscript: (String) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val stub = asyncStub ?: throw IllegalStateException("Stub not ready")
        val recognizerName =
            "projects/${config.projectId}/locations/${config.location}/recognizers/${config.recognizerId}"
        val sampleRate = 16_000
        val chunkMillis = 400
        val windowSamples = sampleRate * chunkMillis / 1000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(windowSamples * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        try {
            audioRecord.startRecording()
            onStatus("Streaming... speak now")

            val requestObserver = stub.streamingRecognize(object : StreamObserver<StreamingRecognizeResponse> {
                override fun onNext(value: StreamingRecognizeResponse) {
                    val transcript = value.resultsList
                        .flatMap { it.alternativesList }
                        .firstOrNull()?.transcript
                    if (!transcript.isNullOrBlank()) {
                        onStatus("Interim: $transcript")
                        onTranscript(transcript)
                    } else {
                        onStatus("No transcript in streaming response")
                    }
                }

                override fun onError(t: Throwable) {
                    Log.w("CloudSttController", "Streaming error", t)
                    onStatus("Stream error: ${t.localizedMessage ?: t}")
                    onError(t.localizedMessage ?: "Cloud STT error")
                }

                override fun onCompleted() {
                    Log.d("CloudSttController", "Streaming completed")
                    onStatus("Stream completed")
                }
            })

            val configReq = StreamingRecognizeRequest.newBuilder()
                .setRecognizer(recognizerName)
                .setStreamingConfig(
                    StreamingRecognitionConfig.newBuilder()
                        .setConfig(
                            RecognitionConfig.newBuilder()
                                .addLanguageCodes(config.languageCode)
                                .setModel(config.model)
                                .setFeatures(
                                    RecognitionFeatures.newBuilder()
                                        .setEnableAutomaticPunctuation(false)
                                        .build(),
                                )
                                .setExplicitDecodingConfig(
                                    ExplicitDecodingConfig.newBuilder()
                                        .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                                        .setSampleRateHertz(16_000)
                                        .setAudioChannelCount(1)
                                        .build(),
                                )
                                .build(),
                        )
                        .setStreamingFeatures(
                            StreamingRecognitionFeatures.newBuilder()
                                .setInterimResults(true)
                                .build(),
                        )
                        .build(),
                )
                .build()
            requestObserver.onNext(configReq)

            val shortBuf = ShortArray(windowSamples)
            while (coroutineContext.isActive) {
                val read = audioRecord.read(shortBuf, 0, windowSamples)
                if (read <= 0) continue
                val audioBytes = encodePcm16(shortBuf, read)
                val audioReq = StreamingRecognizeRequest.newBuilder()
                    .setAudio(ByteString.copyFrom(audioBytes))
                    .build()
                requestObserver.onNext(audioReq)
            }
            requestObserver.onCompleted()
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()
        }
    }

    private suspend fun listenAndStream(
        config: CloudSttConfig,
        onCommand: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val stub = asyncStub ?: throw IllegalStateException("Stub not ready")
        val recognizerName =
            "projects/${config.projectId}/locations/${config.location}/recognizers/${config.recognizerId}"
        val sampleRate = 16_000
        val chunkMillis = 400
        val windowSamples = sampleRate * chunkMillis / 1000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(windowSamples * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        try {
            audioRecord.startRecording()
            onStatus("Listening for: grasp, hold, release")

            val requestObserver = stub.streamingRecognize(object : StreamObserver<StreamingRecognizeResponse> {
                override fun onNext(value: StreamingRecognizeResponse) {
                    val transcript = value.resultsList
                        .flatMap { it.alternativesList }
                        .firstOrNull()?.transcript
                    if (transcript.isNullOrBlank()) return
                    val command = parseCloudCommand(transcript.lowercase())
                    if (command != null) {
                        onStatus("Heard ${cloudVoiceLabel(command)}")
                        onCommand(command)
                    } else {
                        onStatus("Heard: \"$transcript\"")
                    }
                }

                override fun onError(t: Throwable) {
                    Log.w("CloudSttController", "Streaming error", t)
                    onStatus("Stream error: ${t.localizedMessage ?: t}")
                    onError(t.localizedMessage ?: "Cloud STT error")
                }

                override fun onCompleted() {
                    Log.d("CloudSttController", "Streaming completed")
                    onStatus("Stream completed")
                }
            })

            val configReq = StreamingRecognizeRequest.newBuilder()
                .setRecognizer(recognizerName)
                .setStreamingConfig(
                    StreamingRecognitionConfig.newBuilder()
                        .setConfig(
                            RecognitionConfig.newBuilder()
                                .addLanguageCodes(config.languageCode)
                                .setModel(config.model)
                                .setFeatures(
                                    RecognitionFeatures.newBuilder()
                                        .setEnableAutomaticPunctuation(false)
                                        .build(),
                                )
                                .setExplicitDecodingConfig(
                                    ExplicitDecodingConfig.newBuilder()
                                        .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                                        .setSampleRateHertz(16_000)
                                        .setAudioChannelCount(1)
                                        .build(),
                                )
                                .build(),
                        )
                        .setStreamingFeatures(
                            StreamingRecognitionFeatures.newBuilder()
                                .setInterimResults(true)
                                .build(),
                        )
                        .build(),
                )
                .build()
            requestObserver.onNext(configReq)

            val shortBuf = ShortArray(windowSamples)
            while (coroutineContext.isActive) {
                val read = audioRecord.read(shortBuf, 0, windowSamples)
                if (read <= 0) continue
                val audioBytes = encodePcm16(shortBuf, read)
                val audioReq = StreamingRecognizeRequest.newBuilder()
                    .setAudio(ByteString.copyFrom(audioBytes))
                    .build()
                requestObserver.onNext(audioReq)
            }
            requestObserver.onCompleted()
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()
        }
    }

    suspend fun transcribePcm16(
        config: CloudSttConfig,
        samples: ShortArray,
        length: Int = samples.size,
    ): String? = withContext(Dispatchers.IO) {
        ensureBearer(config)?.let { cachedBearer = it } ?: return@withContext null
        ensureStubs(config)
        val stub = blockingStub ?: throw IllegalStateException("Stub not ready")
        val recognizerName =
            "projects/${config.projectId}/locations/${config.location}/recognizers/${config.recognizerId}"
        val audioBytes = encodePcm16(samples, length)
        val request = RecognizeRequest.newBuilder()
            .setRecognizer(recognizerName)
            .setConfig(
                RecognitionConfig.newBuilder()
                    .addLanguageCodes(config.languageCode)
                    .setModel(config.model)
                    .setFeatures(
                        RecognitionFeatures.newBuilder()
                            .setEnableAutomaticPunctuation(false)
                            .build(),
                    )
                    .setExplicitDecodingConfig(
                        ExplicitDecodingConfig.newBuilder()
                            .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                            .setSampleRateHertz(16_000)
                            .setAudioChannelCount(1)
                            .build(),
                    )
                    .build(),
            )
            .setContent(ByteString.copyFrom(audioBytes))
            .build()
        val response: RecognizeResponse = stub.recognize(request)
        return@withContext response.resultsList
            .flatMap { it.alternativesList }
            .firstOrNull()?.transcript
    }

    private fun encodePcm16(samples: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, length)
        return bytes
    }

    private fun parseCloudCommand(text: String): Int? = when {
        text.contains("grasp") -> 0x01
        text.contains("hold") -> 0x02
        text.contains("release") || text.contains("open") -> 0x03
        else -> null
    }

    private fun cloudVoiceLabel(cmd: Int): String = when (cmd) {
        0x01 -> "GRASP"
        0x02 -> "HOLD"
        0x03 -> "RELEASE"
        else -> "CMD $cmd"
    }

    private suspend fun ensureBearer(config: CloudSttConfig): String? = withContext(Dispatchers.IO) {
        cachedBearer?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        config.accessToken?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val endpoint = config.tokenEndpoint?.takeIf { it.isNotBlank() } ?: return@withContext null
        val request = Request.Builder().url(endpoint).get().build()
        httpClient.newCall(request).execute().use { resp ->
            val token = resp.body?.string()?.trim()
            if (!resp.isSuccessful || token.isNullOrBlank()) {
                throw IOException("Token fetch failed HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            }
            cachedBearer = token
            return@withContext token
        }
    }

    private fun ensureStubs(config: CloudSttConfig) {
        if (channel != null && asyncStub != null && blockingStub != null) return
        val bearer = cachedBearer ?: config.accessToken
            ?: throw IllegalStateException("No bearer token")
        val target = "${config.location}-speech.googleapis.com"
        val interceptor = BearerInterceptor(bearer)
        val ch = OkHttpChannelBuilder.forAddress(target, 443)
            .useTransportSecurity()
            .build()
        channel = ch
        asyncStub = SpeechGrpc.newStub(ch).withInterceptors(interceptor)
        blockingStub = SpeechGrpc.newBlockingStub(ch).withInterceptors(interceptor)
    }

    private class BearerInterceptor(private val bearer: String) : ClientInterceptor {
        override fun <ReqT : Any?, RespT : Any?> interceptCall(
            method: io.grpc.MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: io.grpc.Channel,
        ): io.grpc.ClientCall<ReqT, RespT> {
            return object : io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions),
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(AUTH_HEADER, "Bearer $bearer")
                    super.start(responseListener, headers)
                }
            }
        }

        companion object {
            private val AUTH_HEADER: Metadata.Key<String> =
                Metadata.Key.of("Authorization", ASCII_STRING_MARSHALLER)
        }
    }
}
