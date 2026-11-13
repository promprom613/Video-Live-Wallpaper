package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.*
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
        private var blackDrawn = false
        private var visibleNow = false
        private val handler = Handler(Looper.getMainLooper())
        private val blackRetry = Runnable { drawBlack() }
        private var loadTries = 0
        private val loadRetry = object : Runnable {
            override fun run() {
                ensurePlayer()
                if (mediaPlayer == null && loadTries < 60) {
                    handler.postDelayed(this, 3000)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val intentFilter = IntentFilter(VIDEO_PARAMS_CONTROL_ACTION)
            intentFilter.addAction(Intent.ACTION_USER_PRESENT)
            intentFilter.addAction("android.intent.action.USER_UNLOCKED")
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == VIDEO_PARAMS_CONTROL_ACTION) {
                        val mute = intent.getBooleanExtra(KEY_ACTION, false)
                        setVolumeSafe(!mute)
                    } else {
                        // device unlocked / storage ready: load the video if it is not loaded yet
                        ensurePlayer()
                    }
                }
            }.also { broadcastReceiver = it }, intentFilter)
            ensurePlayer()
            if (mediaPlayer == null) handler.postDelayed(loadRetry, 3000)
        }

        // Safe to call any time: loads the video only if there is no working player yet.
        private fun ensurePlayer() {
            if (mediaPlayer != null) return
            loadTries++
            videoFilePath = try {
                this@VideoLiveWallpaperService.openFileInput("video_live_wallpaper_file_path")
                    .bufferedReader().readText()
            } catch (e: Exception) {
                null
            }
            val path = videoFilePath
            if (path.isNullOrEmpty() || !File(path).exists()) {
                videoFilePath = null
                return
            }
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
                        attached = false
                        handler.post {
                            try {
                                mediaPlayer?.release()
                            } catch (e: Exception) {
                            }
                            mediaPlayer = null
                            handler.postDelayed(loadRetry, 3000)
                        }
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
                blackDrawn = false
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

        // Landscape = plain black screen. Drawn with EGL (not lockCanvas), because lockCanvas
        // leaves the surface locked to the CPU and the video could never attach again.
        private fun drawBlack() {
            if (!landscape || !surfaceReady || attached || blackDrawn) return
            try {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val ver = IntArray(2)
                if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) ||
                    num[0] <= 0
                ) return
                val ctx = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                val win = EGL14.eglCreateWindowSurface(
                    display, configs[0], surfaceHolder.surface,
                    intArrayOf(EGL14.EGL_NONE), 0
                )
                if (win == null || win == EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroyContext(display, ctx)
                    return
                }
                try {
                    if (EGL14.eglMakeCurrent(display, win, win, ctx)) {
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        EGL14.eglSwapBuffers(display, win)
                        blackDrawn = true
                    }
                } finally {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    EGL14.eglDestroySurface(display, win)
                    EGL14.eglDestroyContext(display, ctx)
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
                handler.removeCallbacks(blackRetry)
                handler.postDelayed(blackRetry, 150)
                handler.postDelayed(blackRetry, 600)
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
            blackDrawn = false
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
            if (visible && mediaPlayer == null) ensurePlayer()
            updatePlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            handler.removeCallbacks(blackRetry)
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