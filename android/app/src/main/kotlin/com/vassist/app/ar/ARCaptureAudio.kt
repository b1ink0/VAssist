package com.vassist.app.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** PCM capture and post-finalization AAC muxing for AR screen recordings. */
internal class ARCaptureAudio private constructor(
    private val projection: MediaProjection,
    private val includePlayback: Boolean,
    private val includeMicrophone: Boolean,
    private val directory: File,
) {
    companion object {
        private const val RATE = 48_000
        private const val CHANNELS = 2
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AAC_BIT_RATE = 160_000
        private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

        fun start(context: Context, projection: MediaProjection, playback: Boolean, microphone: Boolean): ARCaptureAudio? {
            if (!playback && !microphone) return null
            if (playback && Build.VERSION.SDK_INT < 29) {
                throw IllegalStateException("Media audio capture requires Android 10 or later.")
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Audio permission is required for this recording.")
            }
            val capture = ARCaptureAudio(projection, playback, microphone, context.cacheDir)
            capture.begin()
            return capture
        }
    }

    private val stopped = AtomicBoolean(false)
    private val playbackFile = File(directory, "ar_audio_playback_${System.nanoTime()}.pcm")
    private val microphoneFile = File(directory, "ar_audio_mic_${System.nanoTime()}.pcm")
    private val records = mutableListOf<AudioRecord>()
    private val threads = mutableListOf<Thread>()
    private val sourceStartNs = mutableMapOf<Boolean, Long>()
    @Volatile private var videoStartNs: Long? = null
    @Volatile private var captureError: String? = null

    private fun makeRecord(playback: Boolean): AudioRecord {
        val inputChannels = if (playback) CHANNEL_MASK else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder().setSampleRate(RATE).setEncoding(PCM_ENCODING).setChannelMask(inputChannels).build()
        val minBuffer = AudioRecord.getMinBufferSize(RATE, inputChannels, PCM_ENCODING)
        require(minBuffer > 0) { "The device does not support the requested 48 kHz audio capture." }
        val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(RATE / 2))
        if (playback) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(config)
        } else {
            builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        }
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(if (playback) "Media audio capture could not be initialized." else "Microphone capture could not be initialized.")
        }
        return record
    }

    private fun begin() {
        try {
            val sources = buildList {
                if (includePlayback) add(true to playbackFile)
                if (includeMicrophone) add(false to microphoneFile)
            }
            for ((isPlayback, target) in sources) {
                val record = makeRecord(isPlayback)
                records.add(record)
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio capture did not start." }
                sourceStartNs[isPlayback] = SystemClock.elapsedRealtimeNanos()
                val thread = Thread({ capture(record, target, isPlayback) }, if (isPlayback) "VAssistPlaybackAudio" else "VAssistMicrophoneAudio")
                thread.priority = Thread.MAX_PRIORITY
                threads.add(thread)
                thread.start()
            }
        } catch (e: Exception) {
            stop(); cleanup()
            throw e
        }
    }

    fun markVideoStarted() { videoStartNs = SystemClock.elapsedRealtimeNanos() }

    private fun capture(record: AudioRecord, target: File, stereo: Boolean) {
        try {
            RandomAccessFile(target, "rw").use { output ->
                val buffer = ShortArray(4096)
                while (!stopped.get()) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) throw IllegalStateException("Audio capture ended unexpectedly ($read).")
                    if (read > 0) {
                        val bytes = ByteBuffer.allocate(read * if (stereo) 2 else 4).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            bytes.putShort(buffer[i])
                            if (!stereo) bytes.putShort(buffer[i])
                        }
                        output.write(bytes.array())
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) captureError = e.message ?: "Audio capture failed."
        }
    }

    /** Stops capture, encodes PCM as AAC and remuxes it with the finalized video. */
    fun muxInto(video: File) {
        stop()
        captureError?.let { cleanup(); throw IllegalStateException(it) }
        val pcm = File(directory, "ar_audio_mix_${System.nanoTime()}.pcm")
        val encoded = File(directory, "ar_audio_aac_${System.nanoTime()}.mp4")
        val muxed = File(directory, "ar_audio_video_${System.nanoTime()}.mp4")
        try {
            val durationFrames = videoDurationFrames(video)
            mixPcm(pcm, durationFrames)
            require(pcm.length() >= RATE.toLong() * CHANNELS * 2 / 20) { "No audio samples were captured." }
            encodeAac(pcm, encoded)
            remux(video, encoded, muxed)
            check(muxed.isFile && muxed.length() > 0L) { "Audio and video could not be muxed." }
            val backup = File(directory, "ar_video_original_${System.nanoTime()}.mp4")
            check(video.renameTo(backup)) { "Could not prepare the video for audio finalization." }
            if (!muxed.renameTo(video)) {
                backup.renameTo(video)
                throw IllegalStateException("Could not finalize the audio video file.")
            }
            backup.delete()
        } finally {
            pcm.delete(); encoded.delete(); muxed.delete(); cleanup()
        }
    }

    private fun videoDurationFrames(video: File): Long {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return (format.getLong(MediaFormat.KEY_DURATION) * RATE / 1_000_000L).coerceAtLeast(1L)
                }
            }
            var videoTrack = -1
            var frameRate = 30
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
                    extractor.selectTrack(i)
                    break
                }
            }
            if (videoTrack >= 0) {
                var lastSampleTime = -1L
                while (extractor.sampleTrackIndex >= 0) {
                    lastSampleTime = extractor.sampleTime
                    if (!extractor.advance()) break
                }
                if (lastSampleTime >= 0L) return ((lastSampleTime + 1_000_000L / frameRate) * RATE / 1_000_000L).coerceAtLeast(1L)
            }
        } finally { extractor.release() }
        throw IllegalStateException("The finalized video duration could not be read.")
    }

    private fun mixPcm(output: File, durationFrames: Long) {
        val playback = if (includePlayback) playbackFile.takeIf { it.isFile } else null
        val mic = if (includeMicrophone) microphoneFile.takeIf { it.isFile } else null
        require((!includePlayback || playback != null) && (!includeMicrophone || mic != null)) {
            "A requested audio source produced no data."
        }
        require(durationFrames > 0L) { "The finalized video has no duration." }
        val videoStart = videoStartNs ?: throw IllegalStateException("The video start time is unavailable for audio synchronization.")
        val offsets = mapOf(
            true to (((sourceStartNs[true] ?: videoStart) - videoStart) * RATE / 1_000_000_000L),
            false to (((sourceStartNs[false] ?: videoStart) - videoStart) * RATE / 1_000_000_000L),
        )
        fun hasSamples(file: File?, offsetFrames: Long): Boolean =
            file != null && offsetFrames < durationFrames && file.length() > maxOf(0L, -offsetFrames) * CHANNELS * 2
        require((playback == null || hasSamples(playback, offsets.getValue(true))) &&
            (mic == null || hasSamples(mic, offsets.getValue(false)))) { "A requested audio source produced no samples during the video." }
        val p = playback?.let { RandomAccessFile(it, "r") }
        val m = mic?.let { RandomAccessFile(it, "r") }
        try {
            RandomAccessFile(output, "rw").use { out ->
                    val chunkFrames = 4096
                    var done = 0
                    val pBuf = ShortArray(chunkFrames * CHANNELS)
                    val mBuf = ShortArray(chunkFrames * CHANNELS)
                    val bytes = ByteBuffer.allocate(chunkFrames * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
                    while (done.toLong() < durationFrames) {
                        val count = minOf(chunkFrames.toLong(), durationFrames - done).toInt()
                        readAligned(p, pBuf, count, done, offsets.getValue(true))
                        readAligned(m, mBuf, count, done, offsets.getValue(false))
                        bytes.clear()
                        for (i in 0 until count * CHANNELS) {
                            val a = if (p == null) 0 else pBuf[i].toInt()
                            val b = if (m == null) 0 else mBuf[i].toInt()
                            bytes.putShort(((a + b) / if (p != null && m != null) 2 else 1).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                        }
                        out.write(bytes.array(), 0, count * CHANNELS * 2)
                        done += count
                    }
            }
        } finally {
            runCatching { p?.close() }; runCatching { m?.close() }
        }
    }

    private fun readAligned(input: RandomAccessFile?, target: ShortArray, frames: Int, outputFrame: Int, sourceOffset: Long) {
        val count = frames * CHANNELS
        target.fill(0, 0, count)
        if (input == null) return
        val sourceFrame = outputFrame.toLong() - sourceOffset
        val leadingSilence = (-sourceFrame).coerceIn(0L, frames.toLong()).toInt()
        val firstSourceFrame = sourceFrame.coerceAtLeast(0L)
        val maxFrames = ((input.length() / (CHANNELS * 2L)) - firstSourceFrame).coerceAtLeast(0L)
        val readFrames = minOf((frames - leadingSilence).toLong(), maxFrames).toInt()
        if (readFrames <= 0) return
        input.seek(firstSourceFrame * CHANNELS * 2L)
        val data = ByteArray(readFrames * CHANNELS * 2)
        var offset = 0
        while (offset < data.size) {
            val n = input.read(data, offset, data.size - offset)
            if (n < 0) break
            offset += n
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until readFrames * CHANNELS) target[leadingSilence * CHANNELS + i] = if (buffer.remaining() >= 2) buffer.short else 0
    }

    private fun encodeAac(pcm: File, output: File) {
        val format = MediaFormat.createAudioFormat(AAC_MIME, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(AAC_MIME)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        var pts = 0L
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            RandomAccessFile(pcm, "r").use { input ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgress = android.os.SystemClock.uptimeMillis()
                while (!outputDone) {
                    if (!inputDone) {
                        val index = encoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = encoder.getInputBuffer(index)!!
                            buffer.clear()
                            val read = minOf(buffer.remaining().toLong(), (input.length() - input.filePointer).coerceAtLeast(0L)).toInt()
                            val bytes = ByteArray(read)
                            input.readFully(bytes)
                            buffer.put(bytes)
                            val eos = read == 0
                            val frames = read / (CHANNELS * 2)
                            encoder.queueInputBuffer(index, 0, read, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            pts += frames * 1_000_000L / RATE
                            inputDone = eos
                            lastProgress = android.os.SystemClock.uptimeMillis()
                        }
                    }
                    val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val buffer = encoder.getOutputBuffer(outIndex)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxerStarted) { "AAC encoder did not provide an output format." }
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outIndex, false)
                            lastProgress = android.os.SystemClock.uptimeMillis()
                        }
                    }
                    check(android.os.SystemClock.uptimeMillis() - lastProgress < 15_000L) { "AAC audio encoding stalled." }
                }
            }
        } finally {
            runCatching { encoder.stop() }; encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun remux(video: File, audio: File, output: File) {
        val videoExtractor = android.media.MediaExtractor()
        val audioExtractor = android.media.MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            videoExtractor.setDataSource(video.absolutePath); audioExtractor.setDataSource(audio.absolutePath)
            var videoTrack = -1; var audioTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                    videoTrack = muxer.addTrack(format)
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioExtractor.selectTrack(i); audioTrack = muxer.addTrack(format) }
            }
            check(videoTrack >= 0 && audioTrack >= 0) { "The video or AAC track is missing." }
            muxer.start()
            val fallbackCapacity = if (Build.VERSION.SDK_INT >= 28) 1024 * 1024 else 16 * 1024 * 1024
            var videoBuffer = ByteBuffer.allocateDirect(fallbackCapacity)
            var audioBuffer = ByteBuffer.allocateDirect(fallbackCapacity)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (videoExtractor.sampleTrackIndex >= 0 && Build.VERSION.SDK_INT >= 28) {
                    val needed = videoExtractor.sampleSize
                    if (needed > Int.MAX_VALUE) throw IllegalStateException("A video frame is too large to finalize.")
                    if (needed > videoBuffer.capacity().toLong()) videoBuffer = ByteBuffer.allocateDirect(needed.toInt())
                }
                if (audioExtractor.sampleTrackIndex >= 0 && Build.VERSION.SDK_INT >= 28) {
                    val needed = audioExtractor.sampleSize
                    if (needed > Int.MAX_VALUE) throw IllegalStateException("An audio frame is too large to finalize.")
                    if (needed > audioBuffer.capacity().toLong()) audioBuffer = ByteBuffer.allocateDirect(needed.toInt())
                }
                videoBuffer.clear(); audioBuffer.clear()
                val videoSize = videoExtractor.readSampleData(videoBuffer, 0)
                val audioSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (videoSize < 0 && audioSize < 0) break
                val takeVideo = audioSize < 0 || (videoSize >= 0 && videoExtractor.sampleTime <= audioExtractor.sampleTime)
                val extractor = if (takeVideo) videoExtractor else audioExtractor
                val buffer = if (takeVideo) videoBuffer else audioBuffer
                val size = if (takeVideo) videoSize else audioSize
                val track = if (takeVideo) videoTrack else audioTrack
                info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
        } finally {
            videoExtractor.release(); audioExtractor.release()
            runCatching { muxer.stop() }; muxer.release()
        }
    }

    private fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        records.forEach { runCatching { it.stop() } }
        threads.forEach { runCatching { it.join() } }
        records.forEach { runCatching { it.release() } }
        records.clear(); threads.clear()
    }

    private fun cleanup() { playbackFile.delete(); microphoneFile.delete() }

    fun close() { stop(); cleanup() }
}
