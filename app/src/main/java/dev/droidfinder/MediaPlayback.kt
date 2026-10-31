package dev.droidfinder

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal val Entry.mediaKind: String?
    get() {
        if (folder || isPdfDocument) return null
        return when (fileType(extension, mimeType.orEmpty()).group) {
            FileTypeGroup.VIDEO -> "video"
            FileTypeGroup.AUDIO -> "audio"
            else -> null
        }
    }

internal data class MediaProgress(
    val position: Long = 0,
    val speed: Float = 1f,
    val subtitleUri: String? = null,
    val subtitleMime: String? = null,
    val subtitlesOff: Boolean = false,
    val subtitleKey: String? = null,
)

/** Private, bounded history. A changed file starts fresh; playback always opens paused. */
internal class MediaProgressStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("media-progress", Context.MODE_PRIVATE)

    private fun key(entry: Entry) =
        MessageDigest.getInstance("SHA-256")
            .digest("${entry.path}\n${entry.modified}\n${entry.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun read(entry: Entry): MediaProgress =
        synchronized(lock) {
            runCatching {
                    val value = JSONObject(prefs.getString(key(entry), "{}")!!)
                    MediaProgress(
                        value.optLong("position").coerceAtLeast(0),
                        value.optDouble("speed", 1.0).toFloat().takeIf { it in speeds } ?: 1f,
                        value.optString("subtitle").takeIf { it.isNotBlank() },
                        value.optString("mime").takeIf {
                            it in listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT)
                        },
                        value.optBoolean("off"),
                        value.optString("track").takeIf { it.isNotBlank() },
                    )
                }
                .getOrDefault(MediaProgress())
        }

    fun write(entry: Entry, progress: MediaProgress) =
        synchronized(lock) {
            val key = key(entry)
            val edit =
                prefs
                    .edit()
                    .putString(
                        key,
                        JSONObject()
                            .apply {
                                put("position", progress.position.coerceAtLeast(0))
                                put("speed", progress.speed)
                                put("subtitle", progress.subtitleUri)
                                put("mime", progress.subtitleMime)
                                put("off", progress.subtitlesOff)
                                put("track", progress.subtitleKey)
                                put("updated", System.currentTimeMillis())
                            }
                            .toString(),
                    )
            prefs.all.entries
                .filter { it.key != key }
                .sortedByDescending {
                    runCatching { JSONObject(it.value as String).optLong("updated") }
                        .getOrDefault(0)
                }
                .drop(99)
                .forEach { edit.remove(it.key) }
            edit.apply()
        }

    companion object {
        private val lock = Any()
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
    }
}

internal data class SubtitleTrack(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val key: String,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaPlayback(private val context: Context, val entry: Entry) {
    private val store = MediaProgressStore(context)
    private var progress = store.read(entry)
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    var position by mutableLongStateOf(progress.position)
        private set

    var duration by mutableLongStateOf(0)
        private set

    var ready by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set

    var buffering by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var speed by mutableFloatStateOf(progress.speed)
        private set

    var muted by mutableStateOf(false)
        private set

    var subtitlesOff by mutableStateOf(progress.subtitlesOff)
        private set

    var tracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set

    var selectedTrack by mutableStateOf<String?>(null)
        private set

    private var lastSaved = 0L

    fun start() {
        if (player != null) return
        error = null
        ready = false
        buffering = true
        val media = ExoPlayer.Builder(context).build()
        player = media
        media.setAudioAttributes(AudioAttributes.DEFAULT, true)
        media.setHandleAudioBecomingNoisy(true)
        media.volume = if (muted) 0f else 1f
        media.setPlaybackSpeed(speed)
        media.trackSelectionParameters =
            media.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesOff)
                .build()
        media.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    info: LoadEventInfo,
                    data: MediaLoadData,
                    exception: IOException,
                    wasCanceled: Boolean,
                ) {
                    // Final sidecar load failure is nonfatal in Media3: it disables the text
                    // renderer.
                    // Do not retry the media for transient load errors or interrupt the base
                    // stream.
                    if (
                        player === media &&
                            wasCanceled &&
                            info.dataSpec.uri.toString() == progress.subtitleUri
                    ) {
                        discardSubtitle()
                        media.trackSelectionParameters =
                            media.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                    }
                }
            },
        )
        media.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (this@MediaPlayback.player !== player) return
                    ready =
                        player.playbackState == Player.STATE_READY ||
                            player.playbackState == Player.STATE_ENDED
                    buffering = player.playbackState == Player.STATE_BUFFERING
                    playing = player.isPlaying
                    if (player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
                    if (player.playbackState == Player.STATE_ENDED) {
                        position = duration
                        persist()
                    }
                }

                override fun onPlayerError(exception: PlaybackException) {
                    if (this@MediaPlayback.player !== media) return
                    // A missing/revoked sidecar must not prevent viewing the media itself.
                    if (progress.subtitleUri != null) {
                        discardSubtitle()
                        media.setMediaItem(item(), position)
                        media.prepare()
                        persist()
                    } else {
                        error =
                            "Couldn't play this file. The format may be unsupported or access may have changed."
                        ready = false
                        buffering = false
                        playing = false
                    }
                }

                override fun onTracksChanged(current: Tracks) {
                    tracks =
                        current.groups
                            .filter { it.type == C.TRACK_TYPE_TEXT }
                            .flatMap { group ->
                                (0 until group.length)
                                    .filter { group.isTrackSupported(it) }
                                    .map { index ->
                                        val format = group.getTrackFormat(index)
                                        SubtitleTrack(
                                            group,
                                            index,
                                            format.label
                                                ?: format.language?.let {
                                                    Locale.forLanguageTag(it).displayLanguage
                                                }
                                                ?: "Subtitle ${index + 1}",
                                            if (format.label == "External subtitles") "external"
                                            else
                                                "${format.id}:${format.language}:${format.label}:${format.sampleMimeType}:$index",
                                        )
                                    }
                            }
                    selectedTrack = tracks.firstOrNull { it.group.isTrackSelected(it.index) }?.key
                    val saved = tracks.firstOrNull { it.key == progress.subtitleKey }
                    if (!subtitlesOff && saved != null && selectedTrack != saved.key)
                        chooseTrack(saved)
                }
            },
        )
        media.setMediaItem(item(), position)
        media.prepare()
    }

    private fun item(): MediaItem =
        MediaItem.Builder()
            .setUri(entry.documentUri ?: Uri.fromFile(entry.file))
            .apply {
                val uri = progress.subtitleUri
                val mime = progress.subtitleMime
                if (uri != null && mime != null)
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                                .setMimeType(mime)
                                .setLabel("External subtitles")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                        ),
                    )
            }
            .build()

    fun tick() {
        val media = player ?: return
        if (ready) position = media.currentPosition.coerceAtLeast(0)
        if (playing && System.currentTimeMillis() - lastSaved > 5000) persist()
    }

    fun stop() {
        val media = player ?: return
        tick()
        persist()
        // Null before release: late listener callbacks cannot repopulate stale state.
        player = null
        media.release()
        playing = false
        ready = false
        buffering = false
        tracks = emptyList()
    }

    fun toggle() {
        val media = player ?: return
        if (!ready) return
        if (media.playWhenReady) media.pause()
        else {
            if (media.playbackState == Player.STATE_ENDED) seek(0)
            media.play()
        }
        playing = media.playWhenReady
        if (!playing) {
            tick()
            persist()
        }
    }

    fun seek(to: Long) {
        if (!ready) return
        position = to.coerceIn(0, duration)
        player?.let { media ->
            // Media3 can lose an already-active external SRT cue on an in-buffer seek.
            // Recreate the source at this position while preserving the user's playback state.
            if (
                progress.subtitleMime == MimeTypes.APPLICATION_SUBRIP &&
                    progress.subtitleKey == "external" &&
                    !subtitlesOff
            ) {
                val resume = media.playWhenReady
                media.setMediaItem(item(), position)
                media.prepare()
                media.playWhenReady = resume
            } else media.seekTo(position)
        }
        persist()
    }

    fun changeSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
        persist()
    }

    fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
    }

    fun chooseTrack(track: SubtitleTrack?) {
        subtitlesOff = track == null
        progress = progress.copy(subtitleKey = track?.key ?: progress.subtitleKey)
        player?.let { media ->
            media.trackSelectionParameters =
                media.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesOff)
                    .apply {
                        if (track != null)
                            addOverride(
                                TrackSelectionOverride(track.group.mediaTrackGroup, track.index),
                            )
                    }
                    .build()
        }
        persist()
    }

    fun loadSubtitle(uri: Uri, mime: String) {
        tick()
        progress =
            progress.copy(
                subtitleUri = uri.toString(),
                subtitleMime = mime,
                subtitleKey = "external",
            )
        subtitlesOff = false
        notice = null
        player?.let { media ->
            media.trackSelectionParameters =
                media.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            media.setMediaItem(item(), position)
            media.prepare()
        }
        persist()
    }

    private fun discardSubtitle() {
        progress = progress.copy(subtitleUri = null, subtitleMime = null, subtitleKey = null)
        subtitlesOff = true
        notice = "Subtitle file unavailable or unsupported. Choose another SRT or VTT file."
        persist()
    }

    fun retry() {
        stop()
        start()
    }

    private fun persist() {
        progress = progress.copy(position = position, speed = speed, subtitlesOff = subtitlesOff)
        store.write(entry, progress)
        lastSaved = System.currentTimeMillis()
    }
}

internal fun mediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
