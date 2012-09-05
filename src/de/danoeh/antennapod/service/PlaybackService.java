package de.danoeh.antennapod.service;

import java.io.IOException;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.media.RemoteControlClient.MetadataEditor;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import de.danoeh.antennapod.AppConfig;
import de.danoeh.antennapod.PodcastApp;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.AudioplayerActivity;
import de.danoeh.antennapod.activity.VideoplayerActivity;
import de.danoeh.antennapod.asynctask.FeedImageLoader;
import de.danoeh.antennapod.feed.Feed;
import de.danoeh.antennapod.feed.FeedImage;
import de.danoeh.antennapod.feed.FeedItem;
import de.danoeh.antennapod.feed.FeedManager;
import de.danoeh.antennapod.feed.FeedMedia;
import de.danoeh.antennapod.feed.SimpleChapter;
import de.danoeh.antennapod.receiver.MediaButtonReceiver;
import de.danoeh.antennapod.receiver.PlayerWidget;
import de.danoeh.antennapod.util.Converter;

/** Controls the MediaPlayer that plays a FeedMedia-file */
public class PlaybackService extends Service {
	/** Logging tag */
	private static final String TAG = "PlaybackService";

	/** Contains the id of the media that was played last. */
	public static final String PREF_LAST_PLAYED_ID = "de.danoeh.antennapod.preferences.lastPlayedId";
	/** Contains the feed id of the last played item. */
	public static final String PREF_LAST_PLAYED_FEED_ID = "de.danoeh.antennapod.preferences.lastPlayedFeedId";
	/** True if last played media was streamed. */
	public static final String PREF_LAST_IS_STREAM = "de.danoeh.antennapod.preferences.lastIsStream";
	/** True if last played media was a video. */
	public static final String PREF_LAST_IS_VIDEO = "de.danoeh.antennapod.preferences.lastIsVideo";

	/** Contains the id of the FeedMedia object. */
	public static final String EXTRA_MEDIA_ID = "extra.de.danoeh.antennapod.service.mediaId";
	/** Contains the id of the Feed object of the FeedMedia. */
	public static final String EXTRA_FEED_ID = "extra.de.danoeh.antennapod.service.feedId";
	/** True if media should be streamed. */
	public static final String EXTRA_SHOULD_STREAM = "extra.de.danoeh.antennapod.service.shouldStream";
	/**
	 * True if playback should be started immediately after media has been
	 * prepared.
	 */
	public static final String EXTRA_START_WHEN_PREPARED = "extra.de.danoeh.antennapod.service.startWhenPrepared";

	public static final String ACTION_PLAYER_STATUS_CHANGED = "action.de.danoeh.antennapod.service.playerStatusChanged";

	public static final String ACTION_PLAYER_NOTIFICATION = "action.de.danoeh.antennapod.service.playerNotification";
	public static final String EXTRA_NOTIFICATION_CODE = "extra.de.danoeh.antennapod.service.notificationCode";
	public static final String EXTRA_NOTIFICATION_TYPE = "extra.de.danoeh.antennapod.service.notificationType";

	/** Used in NOTIFICATION_TYPE_RELOAD. */
	public static final int EXTRA_CODE_AUDIO = 1;
	public static final int EXTRA_CODE_VIDEO = 2;

	public static final int NOTIFICATION_TYPE_ERROR = 0;
	public static final int NOTIFICATION_TYPE_INFO = 1;
	public static final int NOTIFICATION_TYPE_BUFFER_UPDATE = 2;
	public static final int NOTIFICATION_TYPE_RELOAD = 3;
	/** The state of the sleeptimer changed. */
	public static final int NOTIFICATION_TYPE_SLEEPTIMER_UPDATE = 4;
	public static final int NOTIFICATION_TYPE_BUFFER_START = 5;
	public static final int NOTIFICATION_TYPE_BUFFER_END = 6;

	/** Is true if service is running. */
	public static boolean isRunning = false;

	private SleepTimer sleepTimer;

	private static final int NOTIFICATION_ID = 1;
	private NotificationCompat.Builder notificationBuilder;

	private AudioManager audioManager;
	private ComponentName mediaButtonReceiver;

	private MediaPlayer player;
	private RemoteControlClient remoteControlClient;

	private FeedMedia media;
	private Feed feed;
	/** True if media should be streamed (Extracted from Intent Extra) . */
	private boolean shouldStream;
	private boolean startWhenPrepared;
	private FeedManager manager;
	private PlayerStatus status;
	private PositionSaver positionSaver;
	private WidgetUpdateWorker widgetUpdater;

	private volatile PlayerStatus statusBeforeSeek;

	private static boolean playingVideo;

	/** True if mediaplayer was paused because it lost audio focus temporarily */
	private boolean pausedBecauseOfTransientAudiofocusLoss;

	private final IBinder mBinder = new LocalBinder();

	public class LocalBinder extends Binder {
		public PlaybackService getService() {
			return PlaybackService.this;
		}
	}

	/**
	 * Returns an intent which starts an audio- or videoplayer, depending on the
	 * type of media that is being played. If the playbackservice is not
	 * running, the type of the last played media will be looked up.
	 * */
	public static Intent getPlayerActivityIntent(Context context) {
		if (isRunning) {
			if (playingVideo) {
				return new Intent(context, VideoplayerActivity.class);
			} else {
				return new Intent(context, AudioplayerActivity.class);
			}
		} else {
			SharedPreferences pref = context.getApplicationContext()
					.getSharedPreferences(PodcastApp.PREF_NAME, 0);
			boolean isVideo = pref.getBoolean(PREF_LAST_IS_VIDEO, false);
			if (isVideo) {
				return new Intent(context, VideoplayerActivity.class);
			} else {
				return new Intent(context, AudioplayerActivity.class);
			}
		}
	}

	/**
	 * Same as getPlayerActivityIntent(context), but here the type of activity
	 * depends on the FeedMedia that is provided as an argument.
	 */
	public static Intent getPlayerActivityIntent(Context context,
			FeedMedia media) {
		if (media.getMime_type().startsWith("video")) {
			return new Intent(context, VideoplayerActivity.class);
		} else {
			return new Intent(context, AudioplayerActivity.class);
		}
	}

	@SuppressLint("NewApi")
	@Override
	public void onCreate() {
		super.onCreate();
		isRunning = true;
		pausedBecauseOfTransientAudiofocusLoss = false;
		status = PlayerStatus.STOPPED;
		if (AppConfig.DEBUG)
			Log.d(TAG, "Service created.");
		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		manager = FeedManager.getInstance();
		player = new MediaPlayer();
		player.setOnPreparedListener(preparedListener);
		player.setOnCompletionListener(completionListener);
		player.setOnSeekCompleteListener(onSeekCompleteListener);
		player.setOnErrorListener(onErrorListener);
		player.setOnBufferingUpdateListener(onBufferingUpdateListener);
		player.setOnInfoListener(onInfoListener);
		mediaButtonReceiver = new ComponentName(getPackageName(),
				MediaButtonReceiver.class.getName());
		audioManager.registerMediaButtonEventReceiver(mediaButtonReceiver);
		if (android.os.Build.VERSION.SDK_INT >= 14) {
			audioManager
					.registerRemoteControlClient(setupRemoteControlClient());
		}
		registerReceiver(headsetDisconnected, new IntentFilter(
				Intent.ACTION_HEADSET_PLUG));

	}

	@SuppressLint("NewApi")
	@Override
	public void onDestroy() {
		super.onDestroy();
		isRunning = false;
		disableSleepTimer();
		unregisterReceiver(headsetDisconnected);
		if (AppConfig.DEBUG)
			Log.d(TAG, "Service is about to be destroyed");
		if (android.os.Build.VERSION.SDK_INT >= 14) {
			audioManager.unregisterRemoteControlClient(remoteControlClient);
		}
		audioManager.unregisterMediaButtonEventReceiver(mediaButtonReceiver);
		audioManager.abandonAudioFocus(audioFocusChangeListener);
		player.release();
		stopWidgetUpdater();
		updateWidget();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private final OnAudioFocusChangeListener audioFocusChangeListener = new OnAudioFocusChangeListener() {

		@Override
		public void onAudioFocusChange(int focusChange) {
			switch (focusChange) {
			case AudioManager.AUDIOFOCUS_LOSS:
				if (AppConfig.DEBUG)
					Log.d(TAG, "Lost audio focus");
				pause(true);
				stopSelf();
				break;
			case AudioManager.AUDIOFOCUS_GAIN:
				if (AppConfig.DEBUG)
					Log.d(TAG, "Gained audio focus");
				if (pausedBecauseOfTransientAudiofocusLoss) {
					play();
				}
				break;
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
				if (AppConfig.DEBUG)
					Log.d(TAG, "Lost audio focus temporarily. Ducking...");
				audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
						AudioManager.ADJUST_LOWER, 0);
				pausedBecauseOfTransientAudiofocusLoss = true;
				break;
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
				if (AppConfig.DEBUG)
					Log.d(TAG, "Lost audio focus temporarily. Pausing...");
				pause(false);
				pausedBecauseOfTransientAudiofocusLoss = true;
			}
		}
	};

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int keycode = intent.getIntExtra(MediaButtonReceiver.EXTRA_KEYCODE, -1);
		if (keycode != -1) {
			if (AppConfig.DEBUG)
				Log.d(TAG, "Received media button event");
			handleKeycode(keycode);
		} else {

			long mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1);
			long feedId = intent.getLongExtra(EXTRA_FEED_ID, -1);
			boolean playbackType = intent.getBooleanExtra(EXTRA_SHOULD_STREAM,
					true);
			if (mediaId == -1 || feedId == -1) {
				Log.e(TAG,
						"Media ID or Feed ID wasn't provided to the Service.");
				if (media == null || feed == null) {
					stopSelf();
				}
				// Intent values appear to be valid
				// check if already playing and playbackType is the same
			} else if (media == null || mediaId != media.getId()
					|| playbackType != shouldStream) {
				pause(true);
				player.reset();
				if (media == null || mediaId != media.getId()) {
					feed = manager.getFeed(feedId);
					media = manager.getFeedMedia(mediaId, feed);
				}

				if (media != null) {
					shouldStream = playbackType;
					startWhenPrepared = intent.getBooleanExtra(
							EXTRA_START_WHEN_PREPARED, false);
					setupMediaplayer();

				} else {
					Log.e(TAG, "Media is null");
					stopSelf();
				}

			} else if (media != null) {
				if (status == PlayerStatus.PAUSED) {
					play();
				}

			} else {
				Log.w(TAG, "Something went wrong. Shutting down...");
				stopSelf();
			}
		}
		return Service.START_NOT_STICKY;
	}

	/** Handles media button events */
	private void handleKeycode(int keycode) {
		switch (keycode) {
		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			if (status == PlayerStatus.PLAYING) {
				pause(false);
			} else if (status == PlayerStatus.PAUSED) {
				play();
			}
			break;
		case KeyEvent.KEYCODE_MEDIA_PLAY:
			if (status == PlayerStatus.PAUSED) {
				play();
			}
			break;
		case KeyEvent.KEYCODE_MEDIA_PAUSE:
			if (status == PlayerStatus.PLAYING) {
				pause(false);
			}
			break;
		}
	}

	/**
	 * Called by a mediaplayer Activity as soon as it has prepared its
	 * mediaplayer.
	 */
	public void setVideoSurface(SurfaceHolder sh) {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Setting display");
		player.setDisplay(null);
		player.setDisplay(sh);
		if (status == PlayerStatus.STOPPED
				|| status == PlayerStatus.AWAITING_VIDEO_SURFACE) {
			try {
				if (shouldStream) {
					player.setDataSource(media.getDownload_url());
					setStatus(PlayerStatus.PREPARING);
					player.prepareAsync();
				} else {
					player.setDataSource(media.getFile_url());
					setStatus(PlayerStatus.PREPARING);
					player.prepare();
				}
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (IllegalStateException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	/** Called when the surface holder of the mediaplayer has to be changed. */
	public void resetVideoSurface() {
		positionSaver.cancel(true);
		player.setDisplay(null);
		player.reset();
		player.release();
		player = new MediaPlayer();
		player.setOnPreparedListener(preparedListener);
		player.setOnCompletionListener(completionListener);
		player.setOnSeekCompleteListener(onSeekCompleteListener);
		player.setOnErrorListener(onErrorListener);
		player.setOnBufferingUpdateListener(onBufferingUpdateListener);
		player.setOnInfoListener(onInfoListener);
		status = PlayerStatus.STOPPED;
		setupMediaplayer();
	}

	/** Called after service has extracted the media it is supposed to play. */
	private void setupMediaplayer() {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Setting up media player");
		try {
			if (media.getMime_type().startsWith("audio")) {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Mime type is audio");
				playingVideo = false;
				if (shouldStream) {
					player.setDataSource(media.getDownload_url());
					setStatus(PlayerStatus.PREPARING);
					player.prepareAsync();
				} else {
					player.setDataSource(media.getFile_url());
					setStatus(PlayerStatus.PREPARING);
					player.prepare();
				}
			} else if (media.getMime_type().startsWith("video")) {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Mime type is video");
				playingVideo = true;
				setStatus(PlayerStatus.AWAITING_VIDEO_SURFACE);
				player.setScreenOnWhilePlaying(true);
			}

		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalStateException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressLint("NewApi")
	private void setupPositionSaver() {
		if (positionSaver != null && !positionSaver.isCancelled()) {
			positionSaver.cancel(true);
		}
		positionSaver = new PositionSaver() {
			@Override
			protected void onCancelled(Void result) {
				super.onCancelled(result);
				positionSaver = null;
			}

			@Override
			protected void onPostExecute(Void result) {
				super.onPostExecute(result);
				positionSaver = null;
			}
		};
		if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.GINGERBREAD_MR1) {
			positionSaver.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		} else {
			positionSaver.execute();
		}
	}

	private MediaPlayer.OnPreparedListener preparedListener = new MediaPlayer.OnPreparedListener() {
		@Override
		public void onPrepared(MediaPlayer mp) {
			if (AppConfig.DEBUG)
				Log.d(TAG, "Resource prepared");
			mp.seekTo(media.getPosition());
			if (media.getDuration() == 0) {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Setting duration of media");
				media.setDuration(mp.getDuration());
			}
			setStatus(PlayerStatus.PREPARED);
			if (startWhenPrepared) {
				play();
			}
		}
	};

	private MediaPlayer.OnSeekCompleteListener onSeekCompleteListener = new MediaPlayer.OnSeekCompleteListener() {

		@Override
		public void onSeekComplete(MediaPlayer mp) {
			if (status == PlayerStatus.SEEKING) {
				setStatus(statusBeforeSeek);
			}

		}
	};

	private MediaPlayer.OnInfoListener onInfoListener = new MediaPlayer.OnInfoListener() {

		@Override
		public boolean onInfo(MediaPlayer mp, int what, int extra) {
			switch (what) {
			case MediaPlayer.MEDIA_INFO_BUFFERING_START:
				sendNotificationBroadcast(NOTIFICATION_TYPE_BUFFER_START, 0);
				return true;
			case MediaPlayer.MEDIA_INFO_BUFFERING_END:
				sendNotificationBroadcast(NOTIFICATION_TYPE_BUFFER_END, 0);
				return true;
			default:
				return false;
			}
		}
	};

	private MediaPlayer.OnErrorListener onErrorListener = new MediaPlayer.OnErrorListener() {
		private static final String TAG = "PlaybackService.onErrorListener";

		@Override
		public boolean onError(MediaPlayer mp, int what, int extra) {
			Log.w(TAG, "An error has occured: " + what);
			if (mp.isPlaying()) {
				pause(true);
			}
			sendNotificationBroadcast(NOTIFICATION_TYPE_ERROR, what);
			stopSelf();
			return true;
		}
	};

	private MediaPlayer.OnCompletionListener completionListener = new MediaPlayer.OnCompletionListener() {

		@Override
		public void onCompletion(MediaPlayer mp) {
			if (AppConfig.DEBUG)
				Log.d(TAG, "Playback completed");
			// Save state
			positionSaver.cancel(true);
			media.setPosition(0);
			manager.markItemRead(PlaybackService.this, media.getItem(), true);
			boolean isInQueue = manager.isInQueue(media.getItem());
			if (isInQueue) {
				manager.removeQueueItem(PlaybackService.this, media.getItem());
			}
			manager.setFeedMedia(PlaybackService.this, media);

			// Prepare for playing next item
			boolean followQueue = PreferenceManager
					.getDefaultSharedPreferences(getApplicationContext())
					.getBoolean(PodcastApp.PREF_FOLLOW_QUEUE, false);
			FeedItem nextItem = manager.getFirstQueueItem();
			boolean playNextItem = isInQueue && followQueue && nextItem != null;
			if (playNextItem) {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Loading next item in queue");
				media = nextItem.getMedia();
				feed = nextItem.getFeed();
				shouldStream = !media.isDownloaded();
				startWhenPrepared = true;
			} else {
				if (AppConfig.DEBUG)
					Log.d(TAG,
							"No more episodes available to play; Reloading current episode");
				startWhenPrepared = false;
				stopForeground(true);
				stopWidgetUpdater();
			}
			int notificationCode = 0;
			if (media.getMime_type().startsWith("audio")) {
				notificationCode = EXTRA_CODE_AUDIO;
				playingVideo = false;
			} else if (media.getMime_type().startsWith("video")) {
				notificationCode = EXTRA_CODE_VIDEO;
			}
			resetVideoSurface();
			refreshRemoteControlClientState();
			sendNotificationBroadcast(NOTIFICATION_TYPE_RELOAD,
					notificationCode);
		}
	};

	private MediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener = new MediaPlayer.OnBufferingUpdateListener() {

		@Override
		public void onBufferingUpdate(MediaPlayer mp, int percent) {
			sendNotificationBroadcast(NOTIFICATION_TYPE_BUFFER_UPDATE, percent);

		}
	};

	public void setSleepTimer(long waitingTime) {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Setting sleep timer to " + Long.toString(waitingTime)
					+ " milliseconds");
		if (sleepTimer != null) {
			sleepTimer.cancel(true);
		}
		sleepTimer = new SleepTimer(waitingTime);
		sleepTimer.executeAsync();
		sendNotificationBroadcast(NOTIFICATION_TYPE_SLEEPTIMER_UPDATE, 0);
	}

	public void disableSleepTimer() {
		if (sleepTimer != null) {
			if (AppConfig.DEBUG)
				Log.d(TAG, "Disabling sleep timer");
			sleepTimer.cancel(true);
			sleepTimer = null;
			sendNotificationBroadcast(NOTIFICATION_TYPE_SLEEPTIMER_UPDATE, 0);
		}
	}

	/**
	 * Saves the current position and pauses playback
	 * 
	 * @param abandonFocus
	 *            is true if the service should release audio focus
	 */
	public void pause(boolean abandonFocus) {
		if (player.isPlaying()) {
			if (AppConfig.DEBUG)
				Log.d(TAG, "Pausing playback.");
			player.pause();
			if (abandonFocus) {
				audioManager.abandonAudioFocus(audioFocusChangeListener);
				disableSleepTimer();
			}
			if (positionSaver != null) {
				positionSaver.cancel(true);
			}
			saveCurrentPosition();
			setStatus(PlayerStatus.PAUSED);
			stopWidgetUpdater();
			stopForeground(true);
		}
	}

	/** Pauses playback and destroys service. Recommended for video playback. */
	public void stop() {
		pause(true);
		stopSelf();
	}

	public void play() {
		if (status == PlayerStatus.PAUSED || status == PlayerStatus.PREPARED
				|| status == PlayerStatus.STOPPED) {
			int focusGained = audioManager.requestAudioFocus(
					audioFocusChangeListener, AudioManager.STREAM_MUSIC,
					AudioManager.AUDIOFOCUS_GAIN);

			if (focusGained == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Audiofocus successfully requested");
				if (AppConfig.DEBUG)
					Log.d(TAG, "Resuming/Starting playback");
				SharedPreferences.Editor editor = getApplicationContext()
						.getSharedPreferences(PodcastApp.PREF_NAME, 0).edit();
				editor.putLong(PREF_LAST_PLAYED_ID, media.getId());
				editor.putLong(PREF_LAST_PLAYED_FEED_ID, feed.getId());
				editor.putBoolean(PREF_LAST_IS_STREAM, shouldStream);
				editor.putBoolean(PREF_LAST_IS_VIDEO, playingVideo);
				editor.commit();

				player.start();
				player.seekTo((int) media.getPosition());
				setStatus(PlayerStatus.PLAYING);
				setupPositionSaver();
				setupWidgetUpdater();
				setupNotification();
				pausedBecauseOfTransientAudiofocusLoss = false;
			} else {
				if (AppConfig.DEBUG)
					Log.d(TAG, "Failed to request Audiofocus");
			}
		}
	}

	private void setStatus(PlayerStatus newStatus) {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Setting status to " + newStatus);
		status = newStatus;
		sendBroadcast(new Intent(ACTION_PLAYER_STATUS_CHANGED));
		updateWidget();
		refreshRemoteControlClientState();
	}

	/** Send ACTION_PLAYER_STATUS_CHANGED without changing the status attribute. */
	private void postStatusUpdateIntent() {
		setStatus(status);
	}

	private void sendNotificationBroadcast(int type, int code) {
		Intent intent = new Intent(ACTION_PLAYER_NOTIFICATION);
		intent.putExtra(EXTRA_NOTIFICATION_TYPE, type);
		intent.putExtra(EXTRA_NOTIFICATION_CODE, code);
		sendBroadcast(intent);
	}

	/** Prepares notification and starts the service in the foreground. */
	private void setupNotification() {
		PendingIntent pIntent = PendingIntent.getActivity(this, 0,
				PlaybackService.getPlayerActivityIntent(this),
				PendingIntent.FLAG_UPDATE_CURRENT);

		Bitmap icon = BitmapFactory.decodeResource(null,
				R.drawable.ic_stat_antenna);
		notificationBuilder = new NotificationCompat.Builder(this)
				.setContentTitle("Mediaplayer Service")
				.setContentText("Click here for more info").setOngoing(true)
				.setContentIntent(pIntent).setLargeIcon(icon)
				.setSmallIcon(R.drawable.ic_stat_antenna);

		startForeground(NOTIFICATION_ID, notificationBuilder.getNotification());
		if (AppConfig.DEBUG)
			Log.d(TAG, "Notification set up");
	}

	/**
	 * Seek a specific position from the current position
	 * 
	 * @param delta
	 *            offset from current position (positive or negative)
	 * */
	public void seekDelta(int delta) {
		seek(player.getCurrentPosition() + delta);
	}

	public void seek(int i) {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Seeking position " + i);
		if (shouldStream) {
			statusBeforeSeek = status;
			setStatus(PlayerStatus.SEEKING);
		}
		player.seekTo(i);
		saveCurrentPosition();
	}

	public void seekToChapter(SimpleChapter chapter) {
		seek((int) chapter.getStart());
	}

	/** Saves the current position of the media file to the DB */
	private synchronized void saveCurrentPosition() {
		if (AppConfig.DEBUG)
			Log.d(TAG,
					"Saving current position to " + player.getCurrentPosition());
		media.setPosition(player.getCurrentPosition());
		manager.setFeedMedia(this, media);
	}

	private void stopWidgetUpdater() {
		if (widgetUpdater != null) {
			widgetUpdater.cancel(true);
		}
		sendBroadcast(new Intent(PlayerWidget.STOP_WIDGET_UPDATE));
	}

	@SuppressLint("NewApi")
	private void setupWidgetUpdater() {
		if (widgetUpdater == null || widgetUpdater.isCancelled()) {
			widgetUpdater = new WidgetUpdateWorker();
			if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.GINGERBREAD_MR1) {
				widgetUpdater.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
			} else {
				widgetUpdater.execute();
			}
		}
	}

	private void updateWidget() {
		if (AppConfig.DEBUG)
			Log.d(TAG, "Sending widget update request");
		PlaybackService.this.sendBroadcast(new Intent(
				PlayerWidget.FORCE_WIDGET_UPDATE));
	}

	public boolean sleepTimerActive() {
		return sleepTimer != null && sleepTimer.isWaiting();
	}

	public long getSleepTimerTimeLeft() {
		if (sleepTimerActive()) {
			return sleepTimer.getWaitingTime();
		} else {
			return 0;
		}
	}

	@SuppressLint("NewApi")
	private RemoteControlClient setupRemoteControlClient() {
		Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		mediaButtonIntent.setComponent(mediaButtonReceiver);
		PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(
				getApplicationContext(), 0, mediaButtonIntent, 0);
		remoteControlClient = new RemoteControlClient(mediaPendingIntent);
		remoteControlClient
				.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE);
		return remoteControlClient;
	}

	/** Refresh player status and metadata. */
	@SuppressLint("NewApi")
	private void refreshRemoteControlClientState() {
		if (android.os.Build.VERSION.SDK_INT >= 14) {
			if (remoteControlClient != null) {
				switch (status) {
				case PLAYING:
					remoteControlClient
							.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
					break;
				case PAUSED:
					remoteControlClient
							.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
					break;
				case STOPPED:
					remoteControlClient
							.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
					break;
				case ERROR:
					remoteControlClient
							.setPlaybackState(RemoteControlClient.PLAYSTATE_ERROR);
					break;
				default:
					remoteControlClient
							.setPlaybackState(RemoteControlClient.PLAYSTATE_BUFFERING);
				}
				if (media != null) {
					MetadataEditor editor = remoteControlClient
							.editMetadata(false);
					editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							media.getItem().getTitle());
					editor.putLong(
							MediaMetadataRetriever.METADATA_KEY_DURATION,
							media.getDuration());
					editor.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							media.getItem().getFeed().getTitle());

					editor.apply();
				}
				if (AppConfig.DEBUG)
					Log.d(TAG, "RemoteControlClient state was refreshed");
			}
		}
	}

	/**
	 * Pauses playback when the headset is disconnected and the preference is
	 * set
	 */
	private BroadcastReceiver headsetDisconnected = new BroadcastReceiver() {
		private static final String TAG = "headsetDisconnected";
		private static final int UNPLUGGED = 0;

		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
				int state = intent.getIntExtra("state", -1);
				if (state != -1) {
					if (AppConfig.DEBUG)
						Log.d(TAG, "Headset plug event. State is " + state);
					boolean pauseOnDisconnect = PreferenceManager
							.getDefaultSharedPreferences(
									getApplicationContext())
							.getBoolean(
									PodcastApp.PREF_PAUSE_ON_HEADSET_DISCONNECT,
									false);
					if (AppConfig.DEBUG)
						Log.d(TAG, "pauseOnDisconnect preference is "
								+ pauseOnDisconnect);
					if (state == UNPLUGGED && pauseOnDisconnect
							&& status == PlayerStatus.PLAYING) {
						if (AppConfig.DEBUG)
							Log.d(TAG,
									"Pausing playback because headset was disconnected");
						pause(true);
					}
				} else {
					Log.e(TAG, "Received invalid ACTION_HEADSET_PLUG intent");
				}
			}
		}
	};

	/** Periodically saves the position of the media file */
	class PositionSaver extends AsyncTask<Void, Void, Void> {
		private static final int WAITING_INTERVALL = 5000;

		@Override
		protected Void doInBackground(Void... params) {
			while (!isCancelled() && player.isPlaying()) {
				try {
					Thread.sleep(WAITING_INTERVALL);
					saveCurrentPosition();
				} catch (InterruptedException e) {
					if (AppConfig.DEBUG)
						Log.d(TAG,
								"Thread was interrupted while waiting. Finishing now...");
					return null;
				} catch (IllegalStateException e) {
					if (AppConfig.DEBUG)
						Log.d(TAG, "Player is in illegal state. Finishing now");
					return null;
				}

			}
			return null;
		}

	}

	/** Notifies the player widget in the specified intervall */
	class WidgetUpdateWorker extends AsyncTask<Void, Void, Void> {
		private static final String TAG = "WidgetUpdateWorker";
		private static final int NOTIFICATION_INTERVALL = 2000;

		@Override
		protected void onProgressUpdate(Void... values) {
			updateWidget();
		}

		@Override
		protected Void doInBackground(Void... params) {
			while (PlaybackService.isRunning && !isCancelled()) {
				publishProgress();
				try {
					Thread.sleep(NOTIFICATION_INTERVALL);
				} catch (InterruptedException e) {
					return null;
				}
			}
			return null;
		}

	}

	/** Sleeps for a given time and then pauses playback. */
	class SleepTimer extends AsyncTask<Void, Void, Void> {
		private static final String TAG = "SleepTimer";
		private static final long UPDATE_INTERVALL = 1000L;
		private volatile long waitingTime;
		private boolean isWaiting;

		public SleepTimer(long waitingTime) {
			super();
			this.waitingTime = waitingTime;
		}

		@Override
		protected Void doInBackground(Void... params) {
			isWaiting = true;
			if (AppConfig.DEBUG)
				Log.d(TAG, "Starting");
			while (!isCancelled()) {
				try {
					Thread.sleep(UPDATE_INTERVALL);
					waitingTime -= UPDATE_INTERVALL;

					if (waitingTime <= 0) {
						if (AppConfig.DEBUG)
							Log.d(TAG, "Waiting completed");
						if (status == PlayerStatus.PLAYING) {
							if (AppConfig.DEBUG)
								Log.d(TAG, "Pausing playback");
							pause(true);
						}
						return null;
					}
				} catch (InterruptedException e) {
					Log.d(TAG, "Thread was interrupted while waiting");
				}
			}
			return null;
		}

		@SuppressLint("NewApi")
		public void executeAsync() {
			if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.GINGERBREAD_MR1) {
				executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
			} else {
				execute();
			}
		}

		@Override
		protected void onCancelled() {
			isWaiting = false;
		}

		@Override
		protected void onPostExecute(Void result) {
			isWaiting = false;
			sendNotificationBroadcast(NOTIFICATION_TYPE_SLEEPTIMER_UPDATE, 0);
		}

		public long getWaitingTime() {
			return waitingTime;
		}

		public boolean isWaiting() {
			return isWaiting;
		}

	}

	public static boolean isPlayingVideo() {
		return playingVideo;
	}

	public boolean isShouldStream() {
		return shouldStream;
	}

	public PlayerStatus getStatus() {
		return status;
	}

	public FeedMedia getMedia() {
		return media;
	}

	public MediaPlayer getPlayer() {
		return player;
	}

	public boolean isStartWhenPrepared() {
		return startWhenPrepared;
	}

	public void setStartWhenPrepared(boolean startWhenPrepared) {
		this.startWhenPrepared = startWhenPrepared;
		postStatusUpdateIntent();
	}

}
