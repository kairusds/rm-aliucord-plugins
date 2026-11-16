package com.github.diamondminer88.plugins

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.FrameLayout.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.discord.api.message.attachment.MessageAttachment
import com.discord.app.AppActivity
import com.discord.stores.StoreMessages
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.google.android.material.card.MaterialCardView
import com.lytefast.flexinput.R
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Suppress("unused")
@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
@AliucordPlugin
class AudioPlayer : Plugin() {
	private val playerBarId = View.generateViewId()
	private val attachmentCardId = Utils.getResId("chat_list_item_attachment_card", "id")

	private val validFileExtensions = setOf(
		"webm", "mp3", "aac", "m4a", "wav", "flac", "wma", "opus", "ogg"
	)

	private val durationCache = ConcurrentHashMap<String, Long>()

	private var appContext: Context? = null
	private var currentPlayer: MediaPlayer? = null
	private var currentPlayingUrl: String? = null
	private var openedAudioSessionId: Int = 0
	private var activeUIUpdater: Timer? = null
	private var resetActiveUI: (() -> Unit)? = null

	private fun msToTime(ms: Long): String {
		if (ms <= 0L) return "0:00"
		val totalSecs = ms / 1000
		val hrs = totalSecs / 3600
		val mins = (totalSecs % 3600) / 60
		val secs = totalSecs % 60

		return if (hrs == 0L)
			String.format("%d:%02d", mins, secs)
		else
			String.format("%d:%02d:%02d", hrs, mins, secs)
	}

	private fun openAudioEffectSession(sessionId: Int) {
		val ctx = appContext ?: return
		if (sessionId <= 0) return
		if (openedAudioSessionId == sessionId) return
		if (openedAudioSessionId > 0) closeAudioEffectSession()
		try {
			ctx.sendBroadcast(
				Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
					putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
					putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
					putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
				}
			)
			openedAudioSessionId = sessionId
		} catch (_: Throwable) {}
	}

	private fun closeAudioEffectSession() {
		val ctx = appContext ?: return
		val sessionId = openedAudioSessionId
		if (sessionId <= 0) return
		openedAudioSessionId = 0
		try {
			ctx.sendBroadcast(
				Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
					putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
					putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
				}
			)
		} catch (_: Throwable) {}
	}

	private fun getFileSizeText(ctx: Context, attachment: MessageAttachment, card: ViewGroup): String {
		try {
			val field = attachment.javaClass.getDeclaredField("size").apply { isAccessible = true }
			val bytes = (field.get(attachment) as? Number)?.toLong() ?: -1L
			if (bytes > 0L) {
				return Formatter.formatShortFileSize(ctx, bytes)
			}
		} catch (_: Throwable) {}

		fun findText(view: View): String? {
			if (view.id == playerBarId) return null
			if (view is TextView) {
				val t = view.text?.toString()?.trim()
				if (!t.isNullOrEmpty() && t != attachment.filename) return t
			} else if (view is ViewGroup) {
				for (i in 0 until view.childCount) {
					findText(view.getChildAt(i))?.let { return it }
				}
			}
			return null
		}
		return findText(card) ?: ""
	}

	private fun createDownloadDrawable(color: Int): Drawable {
		return object : Drawable() {
			private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				this.color = color
				style = Paint.Style.STROKE
				strokeWidth = 2.dp.toFloat()
				strokeCap = Paint.Cap.ROUND
				strokeJoin = Paint.Join.ROUND
			}

			override fun draw(canvas: Canvas) {
				val b = bounds
				val w = b.width().toFloat()
				val h = b.height().toFloat()
				val cx = b.left + w * 0.5f

				canvas.drawLine(cx, b.top + h * 0.15f, cx, b.top + h * 0.65f, paint)
				val headPath = Path().apply {
					moveTo(b.left + w * 0.28f, b.top + h * 0.45f)
					lineTo(cx, b.top + h * 0.68f)
					lineTo(b.left + w * 0.72f, b.top + h * 0.45f)
				}
				canvas.drawPath(headPath, paint)
				val trayPath = Path().apply {
					moveTo(b.left + w * 0.18f, b.top + h * 0.68f)
					lineTo(b.left + w * 0.18f, b.top + h * 0.85f)
					lineTo(b.left + w * 0.82f, b.top + h * 0.85f)
					lineTo(b.left + w * 0.82f, b.top + h * 0.68f)
				}
				canvas.drawPath(trayPath, paint)
			}

			override fun setAlpha(alpha: Int) { paint.alpha = alpha }
			override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
			override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
		}
	}

	private fun stopCurrentPlayer() {
		activeUIUpdater?.cancel()
		activeUIUpdater = null
		closeAudioEffectSession()
		try {
			currentPlayer?.apply {
				if (isPlaying) stop()
				release()
			}
		} catch (_: Throwable) {}
		currentPlayer = null
		currentPlayingUrl = null
		val resetCallback = resetActiveUI
		resetActiveUI = null
		Utils.mainThread.post { resetCallback?.invoke() }
	}

	override fun start(context: Context) {
		appContext = context.applicationContext
		val pauseIcon = ContextCompat.getDrawable(context, com.google.android.exoplayer2.ui.R.b.exo_controls_pause)
		val playIcon = ContextCompat.getDrawable(context, com.google.android.exoplayer2.ui.R.b.exo_controls_play)
		val replayIcon = ContextCompat.getDrawable(context, com.yalantis.ucrop.R.c.ucrop_rotate)

		patcher.after<WidgetChatListAdapterItemAttachment>(
			"configureFileData",
			MessageAttachment::class.java,
			MessageRenderContext::class.java
		) { param ->
			val messageAttachment = param.args[0] as MessageAttachment
			val ext = messageAttachment.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)

			val root = WidgetChatListAdapterItemAttachment.`access$getBinding$p`(this).root as ConstraintLayout
			val card = root.findViewById<MaterialCardView>(attachmentCardId) ?: return@after
			val ctx = root.context

			card.findViewById<View>(playerBarId)?.let { card.removeView(it) }

			if (ext !in validFileExtensions) {
				for (i in 0 until card.childCount) {
					card.getChildAt(i).visibility = View.VISIBLE
				}
				return@after
			}

			val fileUrl = messageAttachment.url
			card.tag = fileUrl

			val fileSizeStr = getFileSizeText(ctx, messageAttachment, card)

			for (i in 0 until card.childCount) {
				val child = card.getChildAt(i)
				if (child.id != playerBarId) child.visibility = View.GONE
			}

			val accentColor = ColorCompat.getThemedColor(ctx, R.b.colorAccent)
			val textColor = ColorCompat.getThemedColor(ctx, R.b.colorTextNormal)
			val mutedColor = ColorCompat.getThemedColor(ctx, R.b.colorTextMuted)

			val playerBar = LinearLayout(ctx).apply {
				id = playerBarId
				orientation = LinearLayout.VERTICAL
				setPadding(10.dp, 8.dp, 10.dp, 8.dp)
				layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
				setOnClickListener {}
			}

			val topRow = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
			}

			val controlContainer = FrameLayout(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp)
				background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(accentColor)
				}
			}

			val buttonView = ImageButton(ctx).apply {
				layoutParams = FrameLayout.LayoutParams(34.dp, 34.dp)
				setImageDrawable(playIcon)
				scaleType = ImageView.ScaleType.CENTER_INSIDE
				setPadding(7.dp, 7.dp, 7.dp, 7.dp)
				imageTintList = ColorStateList.valueOf(Color.WHITE)

				val outValue = TypedValue()
				ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
				setBackgroundResource(outValue.resourceId)
			}

			val loadingSpinner = ProgressBar(ctx).apply {
				layoutParams = FrameLayout.LayoutParams(20.dp, 20.dp).apply {
					gravity = Gravity.CENTER
				}
				indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
				visibility = View.GONE
			}

			controlContainer.addView(buttonView)
			controlContainer.addView(loadingSpinner)

			val sliderView = SeekBar(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
				setPadding(10.dp, 6.dp, 10.dp, 6.dp)
				max = 1000
				progress = 0
				splitTrack = false
				progressTintList = ColorStateList.valueOf(accentColor)
				thumbTintList = ColorStateList.valueOf(accentColor)
				progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 140, 140, 140))

				setOnTouchListener { v, event ->
					when (event.actionMasked) {
						MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
							v.parent?.requestDisallowInterceptTouchEvent(true)
						}
						MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
							v.parent?.requestDisallowInterceptTouchEvent(false)
						}
					}
					false
				}
			}

			val progressView = TextView(ctx, null, 0, R.i.UiKit_TextView).apply {
				text = "0:00 / --:--"
				textSize = 12f
				isSingleLine = true
				setTextColor(textColor)
				layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
			}

			topRow.addView(controlContainer)
			topRow.addView(sliderView)
			topRow.addView(progressView)

			val bottomRow = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
					topMargin = 4.dp
					leftMargin = 2.dp
				}
			}

			val maxFileNameWidth = (ctx.resources.displayMetrics.widthPixels * 0.42f).toInt()
			val fileNameView = TextView(ctx, null, 0, R.i.UiKit_TextView).apply {
				text = messageAttachment.filename
				textSize = 11.5f
				isSingleLine = true
				ellipsize = TextUtils.TruncateAt.MIDDLE
				maxWidth = maxFileNameWidth
				setTextColor(mutedColor)
				layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
			}

			val bulletAndSizeView = TextView(ctx, null, 0, R.i.UiKit_TextView).apply {
				text = if (fileSizeStr.isNotEmpty()) " • $fileSizeStr • " else " • "
				textSize = 11.5f
				isSingleLine = true
				setTextColor(mutedColor)
				layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
			}

			val downloadButton = ImageButton(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp)
				setPadding(3.dp, 3.dp, 3.dp, 3.dp)
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageDrawable(createDownloadDrawable(mutedColor))

				val outValue = TypedValue()
				ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
				setBackgroundResource(outValue.resourceId)

				setOnClickListener {
					val safeName = File(messageAttachment.filename).name.ifEmpty { "audio.$ext" }
					try {
						val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
						if (dm != null) {
							val request = DownloadManager.Request(Uri.parse(fileUrl)).apply {
								setTitle(safeName)
								setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
								setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
							}
							dm.enqueue(request)
							Toast.makeText(ctx, "Downloading $safeName", Toast.LENGTH_SHORT).show()
						} else {
							ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl)))
						}
					} catch (_: Throwable) {
						try {
							ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl)))
						} catch (_: Throwable) {
							Toast.makeText(ctx, "Failed to start download", Toast.LENGTH_SHORT).show()
						}
					}
				}
			}

			bottomRow.addView(fileNameView)
			bottomRow.addView(bulletAndSizeView)
			bottomRow.addView(downloadButton)

			playerBar.addView(topRow)
			playerBar.addView(bottomRow)
			card.addView(playerBar)

			var duration = durationCache[fileUrl] ?: 0L
			var isUserSeeking = false
			var isSeekPending = false

			if (duration > 0L) {
				progressView.text = "0:00 / ${msToTime(duration)}"
			} else {
				Utils.threadPool.execute {
					val fetchedDuration = try {
						val retriever = MediaMetadataRetriever()
						try {
							retriever.setDataSource(fileUrl, HashMap<String, String>())
							retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
						} finally {
							retriever.release()
						}
					} catch (_: Throwable) {
						-1L
					}

					durationCache[fileUrl] = fetchedDuration
					Utils.mainThread.post {
						if (card.tag != fileUrl) return@post
						if (fetchedDuration <= 0L) {
							progressView.text = "0:00 / ??:??"
						} else {
							duration = fetchedDuration
							if (currentPlayingUrl != fileUrl) {
								val previewMs = (sliderView.progress / 1000f * duration).toLong()
								progressView.text = "${msToTime(previewMs)} / ${msToTime(duration)}"
							}
						}
					}
				}
			}

			fun startUpdater() {
				activeUIUpdater?.cancel()
				activeUIUpdater = Timer().apply {
					scheduleAtFixedRate(object : TimerTask() {
						override fun run() {
							val player = currentPlayer ?: return
							if (currentPlayingUrl != fileUrl || isUserSeeking || isSeekPending) return
							try {
								if (player.isPlaying) {
									val pos = player.currentPosition.toLong()
									val dur = if (duration > 0) duration else player.duration.toLong()
									Utils.mainThread.post {
										if (card.tag == fileUrl && !isUserSeeking && !isSeekPending) {
											progressView.text = "${msToTime(pos)} / ${msToTime(dur)}"
											if (dur > 0) sliderView.progress = ((pos * 1000) / dur).toInt()
										}
									}
								}
							} catch (_: Throwable) {}
						}
					}, 0, 200)
				}
			}

			fun bindToActivePlayer() {
				resetActiveUI = {
					if (card.tag == fileUrl) {
						buttonView.visibility = View.VISIBLE
						loadingSpinner.visibility = View.GONE
						buttonView.setImageDrawable(playIcon)
						sliderView.progress = 0
						progressView.text = "0:00 / ${if (duration > 0) msToTime(duration) else "--:--"}"
					}
				}
			}

			if (currentPlayingUrl == fileUrl && currentPlayer != null) {
				val player = currentPlayer!!
				bindToActivePlayer()
				val pos = player.currentPosition.toLong()
				val dur = if (duration > 0) duration else player.duration.toLong()
				progressView.text = "${msToTime(pos)} / ${msToTime(dur)}"
				if (dur > 0) sliderView.progress = ((pos * 1000) / dur).toInt()

				if (player.isPlaying) {
					buttonView.setImageDrawable(pauseIcon)
					startUpdater()
				} else {
					buttonView.setImageDrawable(playIcon)
				}
			}

			sliderView.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
				override fun onStartTrackingTouch(seekBar: SeekBar) {
					isUserSeeking = true
				}

				override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
					if (!fromUser) return
					val dur = if (duration > 0) duration else (currentPlayer?.duration?.toLong() ?: 0L)
					if (dur > 0) {
						val targetMs = (progress / 1000f * dur).toLong()
						progressView.text = "${msToTime(targetMs)} / ${msToTime(dur)}"
					}
				}

				override fun onStopTrackingTouch(seekBar: SeekBar) {
					isUserSeeking = false
					if (currentPlayingUrl == fileUrl && currentPlayer != null) {
						val player = currentPlayer!!
						val dur = if (duration > 0) duration else player.duration.toLong()
						if (dur > 0) {
							val targetMs = (seekBar.progress / 1000f * dur).toInt()
							isSeekPending = true
							player.setOnSeekCompleteListener {
								isSeekPending = false
							}
							player.seekTo(targetMs)
							Utils.mainThread.postDelayed({ isSeekPending = false }, 500)
						}
					}
				}
			})

			buttonView.setOnClickListener {
				if (currentPlayingUrl == fileUrl && currentPlayer != null) {
					val player = currentPlayer!!
					if (player.isPlaying) {
						player.pause()
						closeAudioEffectSession()
						activeUIUpdater?.cancel()
						buttonView.setImageDrawable(playIcon)
					} else {
						val dur = if (duration > 0) duration else player.duration.toLong()
						if (dur > 0) {
							val targetMs = (sliderView.progress / 1000f * dur).toInt()
							if (abs(player.currentPosition - targetMs) > 500) {
								player.seekTo(targetMs)
							}
						}
						openAudioEffectSession(player.audioSessionId)
						player.start()
						buttonView.setImageDrawable(pauseIcon)
						startUpdater()
					}
					return@setOnClickListener
				}

				stopCurrentPlayer()
				currentPlayingUrl = fileUrl
				bindToActivePlayer()

				buttonView.visibility = View.INVISIBLE
				loadingSpinner.visibility = View.VISIBLE

				Utils.threadPool.execute {
					try {
						var playSource = fileUrl
						if (ext == "ogg") {
							val tempFile = File(ctx.cacheDir, "temp_${fileUrl.hashCode()}.ogg")
							if (!tempFile.exists()) {
								Http.simpleDownload(fileUrl, tempFile)
								tempFile.deleteOnExit()
							}
							playSource = tempFile.absolutePath
						}

						val player = MediaPlayer().apply {
							setAudioAttributes(
								AudioAttributes.Builder()
									.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
									.setUsage(AudioAttributes.USAGE_MEDIA)
									.build()
							)
							setDataSource(playSource)
							try {
								playbackParams = PlaybackParams().setSpeed(1.0001f).setPitch(1.0f)
							} catch (_: Throwable) {}
							setOnPreparedListener { mp ->
								if (currentPlayingUrl != fileUrl) {
									mp.release()
									return@setOnPreparedListener
								}
								val actualDur = mp.duration.toLong()
								if (actualDur > 0) {
									duration = actualDur
									durationCache[fileUrl] = actualDur
								}
								if (sliderView.progress > 0 && duration > 0) {
									mp.seekTo((sliderView.progress / 1000f * duration).toInt())
								}
								openAudioEffectSession(mp.audioSessionId)
								mp.start()
								try {
									if (mp.playbackParams.speed == 1.0f) {
										mp.playbackParams = PlaybackParams().setSpeed(1.0001f).setPitch(1.0f)
									}
								} catch (_: Throwable) {}
								Utils.mainThread.post {
									if (card.tag == fileUrl) {
										loadingSpinner.visibility = View.GONE
										buttonView.visibility = View.VISIBLE
										buttonView.setImageDrawable(pauseIcon)
									}
									startUpdater()
								}
							}
							setOnCompletionListener { mp ->
								closeAudioEffectSession()
								activeUIUpdater?.cancel()
								mp.seekTo(0)
								Utils.mainThread.post {
									if (card.tag == fileUrl) {
										sliderView.progress = 0
										progressView.text = "0:00 / ${msToTime(duration)}"
										buttonView.setImageDrawable(replayIcon)
									}
								}
							}
							setOnErrorListener { _, _, _ ->
								stopCurrentPlayer()
								Utils.mainThread.post {
									Toast.makeText(ctx, "Failed to play audio", Toast.LENGTH_SHORT).show()
								}
								true
							}
						}
						currentPlayer = player
						player.prepareAsync()
					} catch (e: Throwable) {
						logger.error("Error initializing audio player", e)
						stopCurrentPlayer()
						Utils.mainThread.post {
							Toast.makeText(ctx, "Error loading audio file", Toast.LENGTH_SHORT).show()
						}
					}
				}
			}
		}

		patcher.after<StoreMessages>("handleChannelSelected", Long::class.javaPrimitiveType!!) {
			stopCurrentPlayer()
		}

		patcher.after<AppActivity>("onCreate", Bundle::class.java) {
			stopCurrentPlayer()
		}

		patcher.after<AppActivity>("onPause") {
			currentPlayer?.let {
				if (it.isPlaying) {
					it.pause()
					closeAudioEffectSession()
					activeUIUpdater?.cancel()
					Utils.mainThread.post { resetActiveUI?.invoke() }
				}
			}
		}
	}

	override fun stop(context: Context) {
		stopCurrentPlayer()
		durationCache.clear()
		patcher.unpatchAll()
		appContext = null
	}
}