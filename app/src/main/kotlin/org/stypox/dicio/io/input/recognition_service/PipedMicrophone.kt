package org.stypox.dicio.io.input.recognition_service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the microphone (16 kHz mono PCM16) on a thread of its own and writes the audio into a
 * pipe, whose [readSide] goes to a speech recognition service as
 * [android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE]. Recording ends on [stop], or when the
 * service closes its end of the pipe.
 */
class PipedMicrophone private constructor(
    private val audioRecord: AudioRecord,
    val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) {
    private val running = AtomicBoolean(true)

    private val thread = Thread({ run() }, "PipedMicrophone").apply { start() }

    private fun run() {
        val buffer = ShortArray(FRAME_SAMPLES)
        val bytes = ByteArray(FRAME_SAMPLES * 2)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                while (running.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        Log.w(TAG, "AudioRecord.read failed: $read")
                        break
                    }
                    for (i in 0 until read) {
                        bytes[2 * i] = (buffer[i].toInt() and 0xff).toByte()
                        bytes[2 * i + 1] = (buffer[i].toInt() shr 8 and 0xff).toByte()
                    }
                    out.write(bytes, 0, read * 2)
                }
            }
        } catch (_: IOException) {
            // the service closed its end of the pipe: it has all the audio it wants
        } finally {
            running.set(false)
            try {
                audioRecord.stop()
            } catch (_: IllegalStateException) {
            }
            audioRecord.release()
        }
    }

    /** Closes this process's copy of [readSide], once the service has received it. */
    fun closeReadSide() {
        try {
            readSide.close()
        } catch (_: IOException) {
        }
    }

    /** Stops recording; the service sees the end of the audio. Safe to call more than once. */
    fun stop() {
        running.set(false)
    }

    companion object {
        private val TAG = PipedMicrophone::class.simpleName
        const val SAMPLE_RATE = 16_000
        /** 32 ms, the frame length of Parakeetype's VAD. */
        private const val FRAME_SAMPLES = 512

        /** Opens the microphone; throws if it cannot be opened. */
        @SuppressLint("MissingPermission") // checked by the UI before listening
        fun start(): PipedMicrophone {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 8),
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IOException("Could not open the microphone")
            }
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                throw IOException("Could not start recording")
            }
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            return PipedMicrophone(audioRecord, readSide, writeSide)
        }
    }
}
