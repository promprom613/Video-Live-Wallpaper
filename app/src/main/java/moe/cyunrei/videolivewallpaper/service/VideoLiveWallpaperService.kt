package moe.cyunrei.videolivewallpaper.service

import android.app.WallpaperManager
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
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
        private var portraitW = 0
        private var portraitH = 0
        private var surfW = 0
        private var surfH = 0
        private var frozen: Bitmap? = null
        private var capturing = false
        private var frameGen = 0
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

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
                mediaPlayer?.release()
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
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun detachSurface() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.pause()
                    it.setSurface(null)
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            attached = false
        }

        // Portrait: video plays on the surface. Landscape: video is detached and a still
        // frame is drawn with the exact portrait framing (no stretch, no zoom, no rotation).
        private fun syncSurface() {
            if (!prepared) return
            if (surfaceReady && !landscape) {
                if (!attached) attachSurface()
                releaseFrozen()
            } else {
                if (landscape && surfaceReady && frozen == null && !capturing) startCapture()
                if (attached) detachSurface()
                drawFrozen()
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
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }

        private fun releaseFrozen() {
            frameGen++
            capturing = false
            frozen?.recycle()
            frozen = null
        }

        private fun startCapture() {
            val path = videoFilePath ?: return
            val pos = try {
                mediaPlayer?.currentPosition ?: 0
            } catch (e: IllegalStateException) {
                0
            }
            capturing = true
            val gen = ++frameGen
            Thread {
                var bmp: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    bmp = r.getFrameAtTime(
                        pos * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                    }
                }
                handler.post {
                    if (gen == frameGen && landscape && surfaceReady) {
                        capturing = false
                        frozen = bmp
                        drawFrozen()
                    } else {
                        bmp?.recycle()
                    }
                }
            }.start()
        }

        private fun drawFrozen() {
            if (!landscape || !surfaceReady || surfW == 0 || surfH == 0) return
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val bmp = frozen
                if (bmp != null && !bmp.isRecycled) {
                    val pw = if (portraitW > 0) portraitW else minOf(surfW, surfH)
                    val ph = if (portraitH > 0) portraitH else maxOf(surfW, surfH)
                    val a = pw.toFloat() / ph.toFloat()
                    // source: center-crop of the frame to the portrait screen aspect
                    val bw = bmp.width.toFloat()
                    val bh = bmp.height.toFloat()
                    val srcW: Float
                    val srcH: Float
                    if (bw / bh > a) {
                        srcH = bh; srcW = bh * a
                    } else {
                        srcW = bw; srcH = bw / a
                    }
                    val sl = ((bw - srcW) / 2f).toInt()
                    val st = ((bh - srcH) / 2f).toInt()
                    val src = Rect(sl, st, sl + srcW.toInt(), st + srcH.toInt())
                    // destination: same portrait picture, centered, full height
                    var dh = canvas.height.toFloat()
                    var dw = dh * a
                    if (dw > canvas.width) {
                        dw = canvas.width.toFloat(); dh = dw / a
                    }
                    val dl = ((canvas.width - dw) / 2f).toInt()
                    val dt = ((canvas.height - dh) / 2f).toInt()
                    val dst = Rect(dl, dt, dl + dw.toInt(), dt + dh.toInt())
                    canvas.drawBitmap(bmp, src, dst, paint)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                }
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
            surfW = width
            surfH = height
            landscape = width > height
            if (!landscape) {
                portraitW = width
                portraitH = height
            }
            syncSurface()
            updatePlayback()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrozen()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            updatePlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            // Keep the player prepared (no release) so resume is instant.
            if (attached) detachSurface()
        }

        override fun onDestroy() {
            super.onDestroy()
            prepared = false
            releaseFrozen()
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