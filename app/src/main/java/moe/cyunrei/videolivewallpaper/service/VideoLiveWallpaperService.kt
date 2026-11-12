package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.*
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
        private var attached = false
        private var landscape = false
        private var visibleNow = false
        private val handler = Handler(Looper.getMainLooper())

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
            initPlayer()
        }

        private fun setVolumeSafe(on: Boolean) {
            try {
                val v = if (on) 1.0f else 0f
                mediaPlayer?.setVolume(v, v)
            } catch (e: Exception) {
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
                        syncSurface()
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
                try {
                    mediaPlayer?.release()
                } catch (e2: Exception) {
                }
                mediaPlayer = null
            }
        }

        private fun attachSurface() {
            val mp = mediaPlayer ?: return
            try {
                mp.setSurface(surfaceHolder.surface)
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                if (!mp.isPlaying) mp.seekTo(mp.currentPosition)
                attached = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun detachSurface() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.pause()
                    it.setSurface(null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            attached = false
        }

        // Landscape = plain black screen. Portrait = video.
        private fun drawBlack() {
            if (!landscape || !surfaceReady || attached) return
            try {
                val holder = surfaceHolder
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    holder.unlockCanvasAndPost(canvas)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        private fun syncSurface() {
            if (landscape && surfaceReady) {
                if (attached) detachSurface()
                drawBlack()
                // retry shortly in case the player had not released the surface yet
                handler.postDelayed({ drawBlack() }, 150)
                handler.postDelayed({ drawBlack() }, 600)
            } else if (surfaceReady && prepared && !attached) {
                attachSurface()
            }
        }

        private fun updatePlayback() {
            val mp = mediaPlayer ?: return
            if (!prepared) return
            try {
                if (visibleNow && surfaceReady && !landscape && attached) {
                    if (!mp.isPlaying) mp.start()
                } else if (mp.isPlaying) {
                    mp.pause()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            syncSurface()
            updatePlayback()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            landscape = width > height
            syncSurface()
            updatePlayback()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawBlack()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            updatePlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            handler.removeCallbacksAndMessages(null)
            // Keep the player prepared (no release) so resume is instant.
            if (attached) detachSurface()
        }

        override fun onDestroy() {
            super.onDestroy()
            prepared = false
            handler.removeCallbacksAndMessages(null)
            try {
                mediaPlayer?.release()
            } catch (e: Exception) {
            }
            mediaPlayer = null
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (e: Exception) {
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