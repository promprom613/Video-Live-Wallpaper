package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.*
import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.io.IOException

class VideoLiveWallpaperService : WallpaperService() {
    internal inner class VideoEngine : Engine() {
        private var mediaPlayer: MediaPlayer? = null
        private var broadcastReceiver: BroadcastReceiver? = null
        private var videoFilePath: String? = null
        private var prepared = false
        private var surfaceReady = false
        private var landscape = false
        private var visibleNow = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            videoFilePath = try {
                this@VideoLiveWallpaperService.openFileInput("video_live_wallpaper_file_path")
                    .bufferedReader().readText()
            } catch (e: Exception) {
                null
            }
            val intentFilter = IntentFilter(VIDEO_PARAMS_CONTROL_ACTION)
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val mute = intent.getBooleanExtra(KEY_ACTION, false)
                    setVolumeSafe(!mute)
                }
            }.also { broadcastReceiver = it }, intentFilter)
            // Player is created and prepared ONCE, in the background, and kept alive.
            initPlayer()
        }

        private fun setVolumeSafe(on: Boolean) {
            try {
                val v = if (on) 1.0f else 0f
                mediaPlayer?.setVolume(v, v)
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun initPlayer() {
            val path = videoFilePath ?: return
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    isLooping = true
                    setOnPreparedListener {
                        prepared = true
                        attachSurface()
                        updatePlayback()
                    }
                    setOnErrorListener { _, _, _ ->
                        prepared = false
                        true
                    }
                    prepareAsync()
                }
                setVolumeSafe(File("$filesDir/unmute").exists())
            } catch (e: Exception) {
                e.printStackTrace()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }

        private fun attachSurface() {
            val mp = mediaPlayer ?: return
            if (!surfaceReady || !prepared) return
            try {
                mp.setSurface(surfaceHolder.surface)
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                // Paused? Seek to the current spot so the frame is drawn on the new surface.
                if (!mp.isPlaying) mp.seekTo(mp.currentPosition)
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun updatePlayback() {
            val mp = mediaPlayer ?: return
            if (!prepared) return
            try {
                if (visibleNow && surfaceReady && !landscape) {
                    if (!mp.isPlaying) mp.start()
                } else if (mp.isPlaying) {
                    mp.pause()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            attachSurface()
            updatePlayback()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // Landscape = freeze the wallpaper (pause), resume when back in portrait.
            landscape = width > height
            updatePlayback()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            updatePlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            // Do NOT release the player: keep it prepared so resume is instant.
            try {
                mediaPlayer?.let { if (it.isPlaying) it.pause() }
                mediaPlayer?.setSurface(null)
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prepared = false
            mediaPlayer?.release()
            mediaPlayer = null
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreateEngine(): Engine {
        return VideoEngine()
    }

    companion object {
        const val VIDEO_PARAMS_CONTROL_ACTION = "moe.cyunrei.livewallpaper"
        private const val KEY_ACTION = "music"
        private const val ACTION_MUSIC_UNMUTE = false
        private const val ACTION_MUSIC_MUTE = true
        fun muteMusic(context: Context) {
            Intent(VIDEO_PARAMS_CONTROL_ACTION).apply {
                putExtra(KEY_ACTION, ACTION_MUSIC_MUTE)
            }.also { context.sendBroadcast(it) }
        }

        fun unmuteMusic(context: Context) {
            Intent(VIDEO_PARAMS_CONTROL_ACTION).apply {
                putExtra(KEY_ACTION, ACTION_MUSIC_UNMUTE)
            }.also {
                context.sendBroadcast(it)
            }
        }

        fun setToWallPaper(context: Context) {
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, VideoLiveWallpaperService::class.java)
                )
            }.also {
                context.startActivity(it)
            }
            try {
                WallpaperManager.getInstance(context).clear()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}