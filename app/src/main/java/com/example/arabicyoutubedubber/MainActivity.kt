package com.example.arabicyoutubedubber

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.arabicyoutubedubber.databinding.ActivityMainBinding
import com.example.arabicyoutubedubber.transcription.AudioExtractor
import com.example.arabicyoutubedubber.transcription.WhisperTranscriber
import com.example.arabicyoutubedubber.transcription.Translator
import com.example.arabicyoutubedubber.transcription.ArabicTTS
import com.example.arabicyoutubedubber.transcription.VideoDubber
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var downloadedVideoUri: Uri? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val downloader = YouTubeDownloader()

    // Initialize pipeline instances
    private val transcriber = WhisperTranscriber()
    private val translator = Translator()
    private val tts = ArabicTTS()
    private val dubber = VideoDubber()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        setupSpeedControls()
    }

    private fun setupClickListeners() {
        binding.btnStart.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (isValidYouTubeUrl(url)) {
                binding.tilUrl.error = null
                startDownloadAndProcessing(url)
            } else {
                binding.tilUrl.error = getString(R.string.error_invalid_url)
                binding.etUrl.requestFocus()
            }
        }
    }

    private fun setupSpeedControls() {
        binding.btnSpeed075.setOnClickListener { changePlaybackSpeed(0.75f) }
        binding.btnSpeed10.setOnClickListener { changePlaybackSpeed(1.0f) }
        binding.btnSpeed125.setOnClickListener { changePlaybackSpeed(1.25f) }
        binding.btnSpeed15.setOnClickListener { changePlaybackSpeed(1.5f) }
    }

    private fun changePlaybackSpeed(speed: Float) {
        player?.let { exoPlayer ->
            // Use PlaybackParameters to change speed (and handle AV sync adjustment implicitly)
            exoPlayer.playbackParameters = PlaybackParameters(speed)
            Log.d(TAG, "Playback speed changed to: $speed")
        }
    }

    private fun isValidYouTubeUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val pattern = "^(https?://)?(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})(.*)?$".toRegex()
        return url.matches(pattern)
    }

    private fun startDownloadAndProcessing(url: String) {
        setUiLoadingState(true)
        binding.tvStatus.text = "جاري تحميل الفيديو من يوتيوب..."

        mainScope.launch {
            try {
                // 1. Download Video
                val originalVideoPath = downloader.downloadVideo(this@MainActivity, url) { progress ->
                    binding.tvStatus.text = "جاري تحميل الفيديو من يوتيوب... $progress%"
                }

                // 2. Extract Audio
                binding.tvStatus.text = "جاري استخراج الصوت من الفيديو..."
                val outputDir = File(cacheDir, "dubber").apply { mkdirs() }
                val originalWavPath = AudioExtractor.extractAudioForWhisper(originalVideoPath, outputDir)

                // 3. Initialize Whisper (downloding from Hugging Face if missing)
                binding.tvStatus.text = "جاري تهيئة نموذج النسخ (Whisper)..."
                transcriber.initialize(this@MainActivity, "ggml-base.en-q5_0.bin") { progress ->
                    binding.tvStatus.text = "جاري تهيئة نموذج النسخ (Whisper)... $progress%"
                }

                // 4. Transcribe Audio
                binding.tvStatus.text = "جاري نسخ الحوار الصوتي (Speech to Text)..."
                val segments = transcriber.transcribeWithTimestamps(originalWavPath) { progress ->
                    binding.tvStatus.text = "جاري نسخ الحوار الصوتي (Speech to Text)... $progress%"
                }

                if (segments.isEmpty()) {
                    throw Exception("لم يتم العثور على أي نصوص أو حوار في المقطع الصوتي.")
                }

                // 5. Translate Segments
                binding.tvStatus.text = "جاري ترجمة الحوار الصوتي إلى العربية..."
                translator.initialize { progress ->
                    binding.tvStatus.text = "جاري ترجمة الحوار الصوتي إلى العربية... $progress%"
                }
                val translatedSegments = translator.translateSegments(segments)

                // 6. Generate Dubbed Audio Track (TTS)
                binding.tvStatus.text = "جاري توليد الصوت العربي المتزامن..."
                tts.initialize(this@MainActivity)
                
                // Calculate original audio duration from file size
                val wavFile = File(originalWavPath)
                val totalDurationMs = (wavFile.length() - 44) / 32 // 16kHz mono 16-bit PCM = 32 bytes per ms
                val dubbedAudioFile = File(outputDir, "dubbed_arabic.wav")
                
                tts.generateDubbedAudioTrack(translatedSegments, totalDurationMs, dubbedAudioFile, cacheDir)

                // 7. Mux Video & Audio
                binding.tvStatus.text = "جاري دمج الصوت العربي مع الفيديو..."
                val mode = when (binding.rgDubbingMode.checkedRadioButtonId) {
                    R.id.rbReplace -> VideoDubber.DubbingMode.REPLACE
                    R.id.rbDualTrack -> VideoDubber.DubbingMode.DUAL_TRACK
                    else -> VideoDubber.DubbingMode.VOICEOVER
                }
                
                val finalVideoPath = File(outputDir, "dubbed_video.mp4").absolutePath
                dubber.dubVideo(originalVideoPath, dubbedAudioFile.absolutePath, finalVideoPath, mode)

                // 8. Play final dubbed video
                val finalFile = File(finalVideoPath)
                if (finalFile.exists() && finalFile.length() > 0) {
                    downloadedVideoUri = Uri.fromFile(finalFile)
                    playDownloadedVideo()
                } else {
                    throw Exception("فشل العثور على الفيديو المدبلج النهائي.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline Error: ", e)
                Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                setUiLoadingState(false)
            }
        }
    }

    private fun setUiLoadingState(isLoading: Boolean) {
        if (isLoading) {
            binding.btnStart.text = ""
            binding.btnStart.isEnabled = false
            binding.btnProgress.visibility = View.VISIBLE
            binding.layoutStatus.visibility = View.VISIBLE
            binding.cardPlayer.visibility = View.GONE
            binding.layoutSpeedControls.visibility = View.GONE
        } else {
            binding.btnStart.text = "ابدأ الدبلجة"
            binding.btnStart.isEnabled = true
            binding.btnProgress.visibility = View.GONE
            binding.layoutStatus.visibility = View.GONE
        }
    }

    private fun playDownloadedVideo() {
        setUiLoadingState(false)
        binding.cardPlayer.visibility = View.VISIBLE
        binding.layoutSpeedControls.visibility = View.VISIBLE
        binding.layoutStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "جاري تشغيل الفيديو المدبلج..."

        // Default to 1.0x playback speed
        binding.toggleSpeed.check(R.id.btnSpeed10)

        downloadedVideoUri?.let { uri ->
            initializePlayer(uri)
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(videoUri: Uri) {
        releasePlayer()

        player = ExoPlayer.Builder(this)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                val mediaItem = MediaItem.fromUri(videoUri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            exoPlayer.release()
        }
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (player == null && downloadedVideoUri != null) {
            playDownloadedVideo()
        }
    }

    override fun onResume() {
        super.onResume()
        if (player == null && downloadedVideoUri != null) {
            playDownloadedVideo()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        releasePlayer()
        transcriber.release()
        translator.close()
        tts.shutdown()
    }
}
