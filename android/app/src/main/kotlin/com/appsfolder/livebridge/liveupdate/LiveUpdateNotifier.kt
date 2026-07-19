package com.appsfolder.livebridge.liveupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.icu.text.BreakIterator
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.appsfolder.livebridge.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object LiveUpdateNotifier {
    const val CHANNEL_ID = "livebridge_promoted_updates"
    private const val TWO_GIS_PACKAGE = "ru.dublgis.dgismobile"

    private const val CHANNEL_NAME = "LiveBridge Updates"
    private const val TAG = "LiveUpdateNotifier"
    private const val MAX_MIRRORED_ACTIONS = 3
    private const val OTP_REPEAT_SUPPRESS_MS = 60_000L
    private const val OTP_AUTOCOPY_COPIED_SHOW_DELAY_MS = 1_000L
    private const val OTP_AUTOCOPY_COPIED_SHOW_DURATION_MS = 1_500L
    private const val CALL_DURATION_REFRESH_MS = 1_000L
    private const val SMART_ISLAND_ANIMATION_MIN_DELAY_MS = 2_000L
    private const val SMART_ISLAND_ANIMATION_MAX_DELAY_MS = 3_000L
    private const val SMART_ISLAND_TOKEN_MAX_LENGTH = 20
    private const val PROGRAMMATIC_MIRROR_CANCEL_GRACE_MS = 2_000L
    private const val FOOD_DELIVERY_AGGREGATE_ENTITY = "delivery"
    private const val LOCKSCREEN_CONTENT_HIDDEN_TEXT = "Content hidden"
    private const val ICON_BACKGROUND_STRIP_THRESHOLD = 250
    private const val ICON_BACKGROUND_FEATHER_THRESHOLD = 200

    private val OTP_CODE_LENGTH = 4..8
    private val NATIVE_IN_CALL_PACKAGES = setOf(
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.google.android.apps.dialer"
    )
    private val WHATSAPP_CALL_MIRROR_BLOCKED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )
    private val DISCORD_PACKAGES = setOf(
        "com.discord",
        "com.discord.alpha",
        "com.discord.beta",
        "com.discord.canary",
        "com.hammerandchisel.discord"
    )
    private val FALLBACK_PRIVACY_REDACTION_PLACEHOLDERS = setOf(
        "sensitive content hidden",
        "content hidden",
        "unlock to view"
    )
    private val externalDeviceDebuggingPattern = Regex(
        """(\badb\b|android\s+debug\s+bridge|usb\s+debug(?:ging)?|wireless\s+debug(?:ging)?|\bdebug(?:ging|ger)?\b|developer\s+options?|usb[-\s]?отладк\p{L}*|беспровод\p{L}*\s+отладк\p{L}*|отладк\p{L}*|параметр\p{L}*\s+разработчик\p{L}*)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val mediaProgressOnlyPattern = Regex("""^\d{1,3}\s*%$""")
    private val callDurationPattern = Regex("""(?<![\d:+-])(?:\d{1,2}:)?\d{1,2}:\d{2}(?!\d)""")
    private val callIncomingTextPattern = Regex(
        """(^|\s)(incoming|ringing|\u0432\u0445\u043e\u0434\u044f\u0449\p{L}*|\u0437\u0432\u043e\u043d\u0438\u0442|\u6765\u7535|\u4f86\u96fb)(\s|$)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val callDialingTextPattern = Regex(
        """^\s*(calling|dialing|\u043d\u0430\u0431\u043e\u0440|\u0432\u044b\u0437\u044b\u0432\u0430\u044e|\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435)\b.*""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val callAnswerActionPattern = Regex(
        """(answer|accept|decline|reject|\u043f\u0440\u0438\u043d\u044f\u0442\u044c|\u043e\u0442\u0432\u0435\u0442\u0438\u0442\u044c|\u043e\u0442\u043a\u043b\u043e\u043d\u0438\u0442\u044c|\u63a5\u542c|\u62d2\u7edd|\u63a5\u807d|\u62d2\u7d55)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val callEndActionPattern = Regex(
        """(^|\s)(end|end\s*call|hang\s*up|hangup|disconnect|leave|\u0437\u0430\u0432\u0435\u0440\u0448\p{L}*|\u043e\u0442\u0431\u043e\u0439|\u0441\u0431\u0440\u043e\u0441\u0438\u0442\u044c|\u043e\u0442\u043a\u043b\u044e\u0447\p{L}*|\u043f\u043e\u043a\u0438\u043d\u0443\u0442\u044c|\u6302\u65ad|\u639b\u65b7|\u7ed3\u675f|\u7d50\u675f|encerrar|terminar)(\s|$)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val callActiveTextPattern = Regex(
        """((?:ongoing|active).{0,40}\bcall\b|call\s+in\s+progress|on\s+call|in\s+call|(?:voice|\u0433\u043e\u043b\u043e\u0441\u043e\u0432\p{L}*(?:\s+\u0441\u0432\u044f\u0437\p{L}*)?).{0,60}(?:connected|\u043f\u043e\u0434\u043a\u043b\u044e\u0447\p{L}*)|\u0440\u0430\u0437\u0433\u043e\u0432\u043e\u0440|\u0438\u0434[\u0435\u0451]\u0442\s+\u0437\u0432\u043e\u043d\u043e\u043a|\u0442\u0435\u043a\u0443\u0449\u0438\u0439\s+\u0437\u0432\u043e\u043d\u043e\u043a|\u901a\u8bdd\u4e2d|\u901a\u8a71\u4e2d)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val callContextTextPattern = Regex(
        """(\bcall\b|\bvoice\s+(?:chat|channel|connection|connected)\b|\u0433\u043e\u043b\u043e\u0441\u043e\u0432\p{L}*(?:\s+\u0441\u0432\u044f\u0437\p{L}*)?|\u0437\u0432\u043e\u043d\u043e\u043a|\u0432\u044b\u0437\u043e\u0432|\u0440\u0430\u0437\u0433\u043e\u0432\u043e\u0440|\u901a\u8bdd|\u901a\u8a71)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val discordVoiceConnectedPattern = Regex(
        """(\bvoice\s+(?:connected|connection|channel)\b|\u0433\u043e\u043b\u043e\u0441\u043e\u0432\p{L}*(?:\s+\u0441\u0432\u044f\u0437\p{L}*)?\s+\u043f\u043e\u0434\u043a\u043b\u044e\u0447\p{L}*)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val weatherCelsiusPattern =
        Regex("""(?:°\s*[cс](?!\p{L})|℃)""", setOf(RegexOption.IGNORE_CASE))
    private val weatherFahrenheitPattern =
        Regex("""(?:°\s*[fф](?!\p{L})|℉)""", setOf(RegexOption.IGNORE_CASE))
    private val explicitOrderEntityPrefixPattern = Regex(
        """(?:#|№|\border\b|\btrip\b|\bride\b|заказ|поездк|订单|訂單|行程)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private const val MEDIA_SYMBOL_PLAY = "\u25B6\uFE0E"
    private const val MEDIA_SYMBOL_PAUSE = "\u2016\uFE0E"
    private const val MEDIA_SYMBOL_PREVIOUS = "\u23EE\uFE0E"
    private const val MEDIA_SYMBOL_NEXT = "\u23ED\uFE0E"
    private val transparentActionIcon by lazy {
        IconCompat.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    }
    private val progressColor = Color.valueOf(15f / 255f, 118f / 255f, 110f / 255f, 1f).toArgb()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateLock = Any()
    private val sbnToAggregateKey = mutableMapOf<String, String>()
    private val aggregateStates = mutableMapOf<String, AggregateState>()
    private val sbnToOtpAggregateKey = mutableMapOf<String, String>()
    private val sbnToOtpSourceKey = mutableMapOf<String, String>()
    private val otpSourceStates = mutableMapOf<String, OtpSourceState>()
    private val otpAggregateStates = mutableMapOf<String, OtpAggregateState>()
    private val otpAnimationGenerations = mutableMapOf<String, Long>()
    private val smartAnimationGenerations = mutableMapOf<String, Long>()
    private val smartAnimationStates = mutableMapOf<String, SmartAnimationState>()
    private val callMirrorStates = mutableMapOf<String, CallMirrorState>()
    private val mirrorKeysByNotificationId = mutableMapOf<Int, String>()
    private val userDismissedMirrorKeys = mutableSetOf<String>()
    private val programmaticMirrorCancelDeadlines = mutableMapOf<Int, Long>()
    private var callMirrorGenerationCounter = 0L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        MirrorNotificationChannel.entries.forEach { channel ->
            ensureMirrorChannel(
                manager = manager,
                context = context,
                channel = channel
            )
        }
    }

    private fun ensureMirrorChannel(
        manager: NotificationManager,
        context: Context,
        channel: MirrorNotificationChannel
    ) {
        val lockscreenVisibility = mirrorChannelLockscreenVisibility(context)
        val current = manager.getNotificationChannel(channel.id)
        if (current == null) {
            manager.createNotificationChannel(createChannel(context, channel))
            return
        }

        val channelText = mirrorChannelText(context, channel)
        val shouldUpdate =
            current.name?.toString() != channelText.name ||
                    current.description != channelText.description ||
                    current.lockscreenVisibility != lockscreenVisibility
        if (!shouldUpdate) {
            return
        }

        current.name = channelText.name
        current.description = channelText.description
        current.lockscreenVisibility = lockscreenVisibility
        manager.createNotificationChannel(current)
    }

    private fun createChannel(
        context: Context,
        channel: MirrorNotificationChannel
    ): NotificationChannel {
        val channelText = mirrorChannelText(context, channel)
        return NotificationChannel(
            channel.id,
            channelText.name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelText.description
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = mirrorChannelLockscreenVisibility(context)
        }
    }

    private fun mirrorChannelLockscreenVisibility(context: Context): Int {
        return if (ConverterPrefs(context).getHideLockscreenContentEnabled()) {
            Notification.VISIBILITY_PRIVATE
        } else {
            Notification.VISIBILITY_PUBLIC
        }
    }

    fun clearRuntimeState() {
        synchronized(stateLock) {
            sbnToAggregateKey.clear()
            aggregateStates.clear()
            sbnToOtpAggregateKey.clear()
            sbnToOtpSourceKey.clear()
            otpSourceStates.clear()
            otpAggregateStates.clear()
            otpAnimationGenerations.clear()
            smartAnimationGenerations.clear()
            smartAnimationStates.clear()
            callMirrorStates.clear()
            mirrorKeysByNotificationId.clear()
            userDismissedMirrorKeys.clear()
            programmaticMirrorCancelDeadlines.clear()
        }
    }

    fun cancelCallMirrors(context: Context): Int {
        val manager = NotificationManagerCompat.from(context)
        val stateNotificationIds = synchronized(stateLock) {
            val keys = callMirrorStates.keys.toList()
            callMirrorStates.clear()
            keys.map(::mirrorIdForKey)
        }
        val activeNotificationIds = runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return@runCatching emptyList()
            notificationManager.activeNotifications
                .filter { it.notification.channelId == MirrorNotificationChannel.CALLS.id }
                .map { it.id }
        }.getOrDefault(emptyList())
        val notificationIds = (stateNotificationIds + activeNotificationIds).distinct()
        notificationIds.forEach { notificationId ->
            cancelMirroredNotification(manager, notificationId)
        }
        return notificationIds.size
    }

    fun maybeMirror(context: Context, prefs: ConverterPrefs, sbn: StatusBarNotification): MirrorResult {
        ensureChannel(context)

        val manager = NotificationManagerCompat.from(context)
        if (!prefs.getConverterEnabled()) {
            val staleAggregateIds = synchronized(stateLock) {
                clearAggregateTrackingForSbnKeyLocked(sbn.key)
            }
            staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
            cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
            return notMirroredResult()
        }
        if (prefs.getSyncDndEnabled() && isDoNotDisturbActive(context)) {
            val staleAggregateIds = synchronized(stateLock) {
                clearAggregateTrackingForSbnKeyLocked(sbn.key)
            }
            staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
            cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
            return notMirroredResult()
        }

        return try {
            if (!passesCoreFilters(context.packageName, sbn)) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                return notMirroredResult()
            }
            if (isNativeInCallNotification(sbn)) {
                cancelMirrorsForIgnoredSource(manager, sbn)
                return notMirroredResult()
            }
            if (isWhatsAppCallMirrorBlocked(sbn)) {
                cancelMirrorsForIgnoredSource(manager, sbn)
                return notMirroredResult()
            }
            val parserDictionary = LiveParserDictionaryLoader.get(context, prefs)
            if (isPrivacyRedactedNotification(sbn.notification, parserDictionary)) {
                return notMirroredResult()
            }
            val appPresentationOverride = AppPresentationOverridesLoader
                .get(prefs)
                .resolve(sbn.packageName.lowercase(Locale.ROOT))
            if (isUserDismissedMirror(sbn.key)) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                return notMirroredResult()
            }
            val source = sbn.notification
            val mediaPlaybackSmartEnabled = prefs.getSmartMediaPlaybackEnabled()
            val bypassesRules = prefs.shouldBypassAllRulesForPackage(sbn.packageName)
            val callMirrorSnapshot = if (prefs.getSmartCallsEnabled()) {
                detectActiveCallMirrorSnapshot(sbn)
            } else {
                null
            }
            if (callMirrorSnapshot != null) {
                if (!bypassesRules &&
                    !passesBaseFilters(prefs, sbn, parserDictionary, mediaPlaybackSmartEnabled)
                ) {
                    val staleAggregateIds = synchronized(stateLock) {
                        clearAggregateTrackingForSbnKeyLocked(sbn.key)
                    }
                    staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                    cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                    return notMirroredResult()
                }
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                val callStartedAtWallClockMs = upsertCallMirrorState(
                    context = context,
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    snapshot = callMirrorSnapshot
                )
                val notification = buildMirroredNotification(
                    context = context,
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.CALLS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    requestPromoted = true,
                    allowNavigationIconHeuristics = false,
                    callMirrorActive = true,
                    callChronometerStartWallClockMs = callStartedAtWallClockMs
                )
                notifyWithPromotionFallback(
                    context = context,
                    manager = manager,
                    notificationId = mirrorIdForKey(sbn.key),
                    mirrorKey = sbn.key,
                    promotedNotification = notification,
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.CALLS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    allowNavigationIconHeuristics = false,
                    callMirrorActive = true,
                    callChronometerStartWallClockMs = callStartedAtWallClockMs
                )
                return mirroredResult()
            } else if (source.category == Notification.CATEGORY_CALL) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                return notMirroredResult()
            }
            if (bypassesRules) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }

                val notification = buildMirroredNotification(
                    context = context,
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.BYPASS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    requestPromoted = true,
                    allowNavigationIconHeuristics = false
                )
                notifyWithPromotionFallback(
                    context = context,
                    manager = manager,
                    notificationId = mirrorIdForKey(sbn.key),
                    mirrorKey = sbn.key,
                    promotedNotification = notification,
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.BYPASS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    allowNavigationIconHeuristics = false
                )
                return mirroredResult()
            }
            if (!passesBaseFilters(prefs, sbn, parserDictionary, mediaPlaybackSmartEnabled)) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                return notMirroredResult()
            }
            val nativeProgressEnabled = prefs.getOnlyWithProgress()
            val hasNativeProgress = nativeProgressEnabled &&
                    hasEffectiveProgress(sbn.packageName, source)
            val animatedIslandEnabled = prefs.getAnimatedIslandEnabled()
            val isMediaPlaybackNotification = mediaPlaybackSmartEnabled &&
                    isLikelyMediaPlaybackNotification(source)
            val mediaPlaybackSnapshot = if (isMediaPlaybackNotification) {
                extractMediaPlaybackSnapshot(
                    context = context,
                    notification = source,
                    sourcePackageName = sbn.packageName
                )
            } else {
                null
            }

            val otpMatch = if (!isMediaPlaybackNotification &&
                !hasNativeProgress &&
                prefs.getOtpDetectionEnabled() &&
                prefs.isOtpPackageAllowed(sbn.packageName)
            ) {
                detectOtpCode(sbn.packageName, source, parserDictionary)
            } else {
                null
            }

            val smartMatch = if (!isMediaPlaybackNotification &&
                otpMatch == null &&
                prefs.getSmartStatusDetectionEnabled()
            ) {
                detectSmartStage(
                    packageName = sbn.packageName,
                    source = source,
                    parserDictionary = parserDictionary,
                    taxiEnabled = prefs.getSmartTaxiEnabled(),
                    deliveryEnabled = prefs.getSmartDeliveryEnabled(),
                    navigationEnabled = prefs.getSmartNavigationEnabled(),
                    weatherEnabled = prefs.getSmartWeatherEnabled(),
                    externalDevicesEnabled = prefs.getSmartExternalDevicesEnabled(),
                    externalDevicesIgnoreDebugging = prefs.getSmartExternalDevicesIgnoreDebugging(),
                    vpnEnabled = prefs.getSmartVpnEnabled(),
                    smartPackageAllowed = prefs.isSmartPackageAllowed(sbn.packageName),
                    hasNativeProgress = hasNativeProgress
                )
            } else {
                null
            }

            val textProgressMatch = if (!isMediaPlaybackNotification &&
                !hasNativeProgress &&
                otpMatch == null &&
                prefs.getTextProgressEnabled()
            ) {
                detectTextProgress(
                    packageName = sbn.packageName,
                    source = source,
                    parserDictionary = parserDictionary
                )
            } else {
                null
            }

            val shouldSuppressNonTrafficVpn = !isMediaPlaybackNotification &&
                    otpMatch == null &&
                    smartMatch == null &&
                    textProgressMatch == null &&
                    prefs.getSmartVpnEnabled() &&
                    shouldSuppressVpnWithoutTraffic(
                        packageName = sbn.packageName,
                        source = source,
                        parserDictionary = parserDictionary
                    )
            if (shouldSuppressNonTrafficVpn) {
                val staleAggregateIds = synchronized(stateLock) {
                    clearAggregateTrackingForSbnKeyLocked(sbn.key)
                }
                staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                return notMirroredResult()
            }

            when {
                isMediaPlaybackNotification -> {
                    val staleAggregateIds = synchronized(stateLock) {
                        clearAggregateTrackingForSbnKeyLocked(sbn.key)
                    }
                    staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }

                    val mediaProgressOverride = mediaPlaybackSnapshot?.toProgressOverride()
                    val mediaShortText = mediaPlaybackSnapshot?.let(::buildMediaPlaybackShortText)
                    val mediaTitle = mediaPlaybackSnapshot?.title
                    val mediaText = mediaPlaybackSnapshot?.artist
                    val mediaLargeIcon = mediaPlaybackSnapshot?.albumArt
                    val notification = buildMirroredNotification(
                        context = context,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.MEDIA_PLAYBACK,
                        progressOverride = mediaProgressOverride,
                        otpOverride = null,
                        smartShortTextOverride = mediaShortText,
                        requestPromoted = true,
                        allowNavigationIconHeuristics = false,
                        preferMediaControls = true,
                        mediaPlaybackIsPlaying = mediaPlaybackSnapshot?.isPlaying,
                        titleOverride = mediaTitle,
                        textOverride = mediaText,
                        largeIconOverride = mediaLargeIcon
                    )
                    notifyWithPromotionFallback(
                        context = context,
                        manager = manager,
                        notificationId = mirrorIdForKey(sbn.key),
                        mirrorKey = sbn.key,
                        promotedNotification = notification,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.MEDIA_PLAYBACK,
                        progressOverride = mediaProgressOverride,
                        otpOverride = null,
                        smartShortTextOverride = mediaShortText,
                        allowNavigationIconHeuristics = false,
                        preferMediaControls = true,
                        mediaPlaybackIsPlaying = mediaPlaybackSnapshot?.isPlaying,
                        titleOverride = mediaTitle,
                        textOverride = mediaText,
                        largeIconOverride = mediaLargeIcon
                    )
                    mirroredResult()
                }

                otpMatch != null -> {
                    if (isUserDismissedMirror(otpMatch.aggregateKey)) {
                        return notMirroredResult()
                    }
                    val routeState = synchronized(stateLock) {
                        val staleAggregateIds = mutableListOf<Int>()
                        staleAggregateIds.addAll(clearSmartTrackingForSbnKeyLocked(sbn.key))

                        val sourceKey = otpSourceKeyForPackage(sbn.packageName)
                        val sourceState = otpSourceStates[sourceKey]
                        if (sourceState != null &&
                            sourceState.sbnKey != sbn.key &&
                            sbn.postTime < sourceState.postTimeMs
                        ) {
                            staleAggregateIds.addAll(clearOtpTrackingForSbnKeyLocked(sbn.key))
                            OtpRouteState(
                                staleAggregateIds = staleAggregateIds,
                                shouldPublish = false,
                                shouldAutoCopy = false,
                                otpCode = otpMatch.code
                            )
                        } else {
                            staleAggregateIds.addAll(clearOtpTrackingForSourceLocked(sourceKey, sbn.key))

                            val existingOtpAggregateKey = sbnToOtpAggregateKey[sbn.key]
                            if (existingOtpAggregateKey != null && existingOtpAggregateKey != otpMatch.aggregateKey) {
                                staleAggregateIds.addAll(clearOtpTrackingForSbnKeyLocked(sbn.key))
                            }

                            val state = otpAggregateStates.getOrPut(otpMatch.aggregateKey) { OtpAggregateState() }
                            state.activeSbnKeys.add(sbn.key)
                            sbnToOtpAggregateKey[sbn.key] = otpMatch.aggregateKey
                            sbnToOtpSourceKey[sbn.key] = sourceKey
                            otpSourceStates[sourceKey] = OtpSourceState(
                                sbnKey = sbn.key,
                                aggregateKey = otpMatch.aggregateKey,
                                postTimeMs = sbn.postTime
                            )

                            val now = System.currentTimeMillis()
                            val shouldPublish =
                                state.lastRenderedAtMs == 0L ||
                                        now - state.lastRenderedAtMs >= OTP_REPEAT_SUPPRESS_MS
                            if (shouldPublish) {
                                state.lastRenderedAtMs = now
                            }
                            val shouldAutoCopy =
                                prefs.getOtpAutoCopyEnabled() &&
                                        shouldAutoCopyOtpLocked(state, otpMatch.code)
                            OtpRouteState(
                                staleAggregateIds = staleAggregateIds,
                                shouldPublish = shouldPublish,
                                shouldAutoCopy = shouldAutoCopy,
                                otpCode = otpMatch.code
                            )
                        }
                    }
                    routeState.staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }

                    if (routeState.shouldPublish) {
                        val notification = buildMirroredNotification(
                            context = context,
                            sbn = sbn,
                            appPresentationOverride = appPresentationOverride,
                            mirrorChannel = MirrorNotificationChannel.OTP_CODES,
                            progressOverride = null,
                            otpOverride = otpMatch,
                            smartShortTextOverride = null,
                            requestPromoted = true
                        )
                        notifyWithPromotionFallback(
                            context = context,
                            manager = manager,
                            notificationId = mirrorIdForKey(otpMatch.aggregateKey),
                            mirrorKey = otpMatch.aggregateKey,
                            promotedNotification = notification,
                            sbn = sbn,
                            appPresentationOverride = appPresentationOverride,
                            mirrorChannel = MirrorNotificationChannel.OTP_CODES,
                            progressOverride = null,
                            otpOverride = otpMatch,
                            smartShortTextOverride = null
                        )
                    }
                    if (routeState.shouldAutoCopy) {
                        copyOtpToClipboard(context, routeState.otpCode)
                        if (routeState.shouldPublish) {
                            startOtpAutoCopyAnimation(
                                context = context,
                                manager = manager,
                                sbn = sbn,
                                appPresentationOverride = appPresentationOverride,
                                otpMatch = otpMatch
                            )
                        }
                    }
                    if (routeState.shouldPublish) {
                        mirroredResult(dedupKind = MirrorDedupKind.OTP)
                    } else {
                        notMirroredResult()
                    }
                }

                textProgressMatch != null -> {
                    val staleAggregateIds = synchronized(stateLock) {
                        clearAggregateTrackingForSbnKeyLocked(sbn.key)
                    }
                    staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }

                    val notification = buildMirroredNotification(
                        context = context,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.PROGRESS_NOTIFICATIONS,
                        progressOverride = ProgressOverride(
                            value = textProgressMatch.percent,
                            max = 100
                        ),
                        otpOverride = null,
                        smartShortTextOverride = textProgressMatch.shortText,
                        requestPromoted = true
                    )
                    notifyWithPromotionFallback(
                        context = context,
                        manager = manager,
                        notificationId = mirrorIdForKey(sbn.key),
                        mirrorKey = sbn.key,
                        promotedNotification = notification,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.PROGRESS_NOTIFICATIONS,
                        progressOverride = ProgressOverride(
                            value = textProgressMatch.percent,
                            max = 100
                        ),
                        otpOverride = null,
                        smartShortTextOverride = textProgressMatch.shortText
                    )
                    mirroredResult()
                }

                smartMatch != null -> {
                    if (isUserDismissedMirror(smartMatch.aggregateKey)) {
                        return notMirroredResult()
                    }
                    val routeState = synchronized(stateLock) {
                        val staleAggregateIds = mutableListOf<Int>()
                        staleAggregateIds.addAll(clearOtpTrackingForSbnKeyLocked(sbn.key))

                        val existingSmartAggregateKey = sbnToAggregateKey[sbn.key]
                        if (existingSmartAggregateKey != null &&
                            existingSmartAggregateKey != smartMatch.aggregateKey
                        ) {
                            staleAggregateIds.addAll(clearSmartTrackingForSbnKeyLocked(sbn.key))
                        }

                        val state = aggregateStates.getOrPut(smartMatch.aggregateKey) {
                            AggregateState(smartMatch.stageValue, smartMatch.maxStage)
                        }
                        state.activeSbnKeys.add(sbn.key)
                        state.sourcesBySbnKey[sbn.key] = SmartSourceEntry(
                            stageValue = smartMatch.stageValue,
                            postTimeMs = sbn.postTime,
                            sbn = sbn,
                            compactOrderCode = smartMatch.compactOrderCode
                        )
                        state.maxStageSeen = if (smartMatch.keepHighestStage) {
                            maxOf(state.maxStageSeen, smartMatch.stageValue)
                        } else {
                            smartMatch.stageValue
                        }
                        sbnToAggregateKey[sbn.key] = smartMatch.aggregateKey
                        val sourceEntry = selectSmartSourceEntryLocked(
                            aggregateState = state,
                            keepHighestStage = smartMatch.keepHighestStage
                        )

                        SmartRouteState(
                            staleAggregateIds = staleAggregateIds,
                            stageValue = state.maxStageSeen,
                            stageMax = state.maxStage,
                            compactOrderCode = sourceEntry?.compactOrderCode ?: smartMatch.compactOrderCode,
                            sourceSbn = sourceEntry?.sbn ?: sbn
                        )
                    }
                    routeState.staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                    val sourceSbn = routeState.sourceSbn
                    val sourceNotification = sourceSbn.notification
                    val smartRuleId = smartRuleIdFromAggregateKey(smartMatch.aggregateKey)
                    val mirrorChannel = mirrorChannelForSmartRule(smartRuleId)
                    val dedupKind = if (isNotificationDedupEligibleSmartRule(smartRuleId)) {
                        MirrorDedupKind.STATUS
                    } else {
                        MirrorDedupKind.NONE
                    }
                    val defaultSmartStatus = smartShortStatusText(
                        context = context,
                        ruleId = smartRuleId,
                        stageValue = routeState.stageValue,
                        parserDictionary = parserDictionary
                    )
                    val vpnTraffic = if (smartRuleId == "vpn") {
                        extractVpnTrafficSpeeds(
                            notification = sourceNotification,
                            fallbackTitle = sourceSbn.packageName,
                            parserDictionary = parserDictionary
                        )
                    } else {
                        null
                    }
                    val smartStatusText = when (smartRuleId) {
                        "navigation" -> extractNavigationDistanceText(
                            notification = sourceNotification,
                            fallbackTitle = sourceSbn.packageName,
                            parserDictionary = parserDictionary
                        ) ?: defaultSmartStatus

                        "weather" -> extractWeatherTemperatureText(
                            notification = sourceNotification,
                            fallbackTitle = sourceSbn.packageName,
                            parserDictionary = parserDictionary
                        ) ?: defaultSmartStatus

                        "external_device" -> extractExternalDeviceStatusText(
                            context = context,
                            notification = sourceNotification,
                            fallbackTitle = sourceSbn.packageName,
                            stageValue = routeState.stageValue,
                            parserDictionary = parserDictionary
                        ) ?: defaultSmartStatus

                        "vpn" -> formatDominantVpnTrafficText(vpnTraffic) ?: defaultSmartStatus

                        else -> defaultSmartStatus
                    } ?: routeState.compactOrderCode
                    val smartProgressOverride = if (
                        smartRuleId == "weather" ||
                        smartRuleId == "external_device" ||
                        smartRuleId == "vpn"
                    ) {
                        null
                    } else {
                        ProgressOverride(routeState.stageValue, routeState.stageMax)
                    }

                    val notification = buildMirroredNotification(
                        context = context,
                        sbn = sourceSbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = mirrorChannel,
                        progressOverride = smartProgressOverride,
                        otpOverride = null,
                        smartShortTextOverride = smartStatusText,
                        smartRuleId = smartRuleId,
                        requestPromoted = true
                    )
                    notifyWithPromotionFallback(
                        context = context,
                        manager = manager,
                        notificationId = mirrorIdForKey(smartMatch.aggregateKey),
                        mirrorKey = smartMatch.aggregateKey,
                        promotedNotification = notification,
                        sbn = sourceSbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = mirrorChannel,
                        progressOverride = smartProgressOverride,
                        otpOverride = null,
                        smartShortTextOverride = smartStatusText,
                        smartRuleId = smartRuleId
                    )
                    if (animatedIslandEnabled) {
                        val animatedTokens = buildSmartAnimatedIslandTokens(
                            ruleId = smartRuleId,
                            notification = sourceNotification,
                            fallbackTitle = sourceSbn.packageName,
                            primaryStatus = smartStatusText,
                            compactOrderCode = routeState.compactOrderCode,
                            parserDictionary = parserDictionary
                        )
                        startSmartIslandAnimation(
                            context = context,
                            manager = manager,
                            aggregateKey = smartMatch.aggregateKey,
                            sbn = sourceSbn,
                            appPresentationOverride = appPresentationOverride,
                            mirrorChannel = mirrorChannel,
                            progressOverride = smartProgressOverride,
                            smartRuleId = smartRuleId,
                            tokens = animatedTokens,
                            initialToken = smartStatusText
                        )
                    }
                    mirroredResult(dedupKind = dedupKind)
                }

                hasNativeProgress -> {
                    val staleAggregateIds = synchronized(stateLock) {
                        clearAggregateTrackingForSbnKeyLocked(sbn.key)
                    }
                    staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }

                    val notification = buildMirroredNotification(
                        context = context,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.PROGRESS_NOTIFICATIONS,
                        progressOverride = null,
                        otpOverride = null,
                        smartShortTextOverride = null,
                        requestPromoted = true
                    )
                    notifyWithPromotionFallback(
                        context = context,
                        manager = manager,
                        notificationId = mirrorIdForKey(sbn.key),
                        mirrorKey = sbn.key,
                        promotedNotification = notification,
                        sbn = sbn,
                        appPresentationOverride = appPresentationOverride,
                        mirrorChannel = MirrorNotificationChannel.PROGRESS_NOTIFICATIONS,
                        progressOverride = null,
                        otpOverride = null,
                        smartShortTextOverride = null
                    )
                    mirroredResult()
                }

                else -> {
                    val staleAggregateIds = synchronized(stateLock) {
                        clearAggregateTrackingForSbnKeyLocked(sbn.key)
                    }
                    staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
                    cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
                    notMirroredResult()
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to mirror notification: ${sbn.key}", error)
            notMirroredResult()
        }
    }

    private fun isDoNotDisturbActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return try {
            when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true

                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun detectActiveCallMirrorSnapshot(sbn: StatusBarNotification): CallMirrorSnapshot? {
        val source = sbn.notification
        val ongoing = sbn.isOngoing ||
                source.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
                !sbn.isClearable
        if (!ongoing) {
            return null
        }

        val contentTexts = collectCallContentTexts(
            notification = source,
            fallbackTitle = sbn.packageName
        )
        val actionTexts = collectCallActionTexts(source)
        if (hasIncomingOrDialingCallMarker(contentTexts, actionTexts)) {
            return null
        }

        val timeSeed = resolveCallTimeSeed(source, contentTexts)
        val isDiscordVoiceConnection = isDiscordVoiceConnectionNotification(
            sbn = sbn,
            contentTexts = contentTexts,
            actionTexts = actionTexts
        )
        val hasEndCallAction = actionTexts.any(callEndActionPattern::containsMatchIn)
        val hasActiveCallText = contentTexts.any(callActiveTextPattern::containsMatchIn)
        val hasCallContext =
            source.category == Notification.CATEGORY_CALL ||
                    contentTexts.any(callContextTextPattern::containsMatchIn) ||
                    isDiscordVoiceConnection
        if (!hasCallContext) {
            return null
        }
        if (source.category != Notification.CATEGORY_CALL &&
            !hasEndCallAction &&
            !isDiscordVoiceConnection
        ) {
            return null
        }
        if (!timeSeed.hasExplicitSource &&
            !hasEndCallAction &&
            !hasActiveCallText &&
            !isDiscordVoiceConnection
        ) {
            return null
        }

        return CallMirrorSnapshot(
            explicitStartWallClockMs = timeSeed.explicitStartWallClockMs,
            elapsedDurationMs = timeSeed.elapsedDurationMs
        )
    }

    private fun isNativeInCallNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName.lowercase(Locale.ROOT)
        return packageName in NATIVE_IN_CALL_PACKAGES &&
                sbn.notification.category == Notification.CATEGORY_CALL
    }

    private fun isWhatsAppCallMirrorBlocked(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName.lowercase(Locale.ROOT)
        if (packageName !in WHATSAPP_CALL_MIRROR_BLOCKED_PACKAGES) {
            return false
        }

        val source = sbn.notification
        if (source.category == Notification.CATEGORY_CALL) {
            return true
        }

        val ongoing = sbn.isOngoing ||
                source.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
                !sbn.isClearable
        if (!ongoing) {
            return false
        }

        val actionTexts = collectCallActionTexts(source)
        if (actionTexts.any(callEndActionPattern::containsMatchIn)) {
            return true
        }

        val contentTexts = collectCallContentTexts(
            notification = source,
            fallbackTitle = sbn.packageName
        )
        return contentTexts.any(callActiveTextPattern::containsMatchIn) &&
                contentTexts.any(callDurationPattern::containsMatchIn)
    }

    private fun isDiscordVoiceConnectionNotification(
        sbn: StatusBarNotification,
        contentTexts: List<String>,
        actionTexts: List<String>
    ): Boolean {
        val packageName = sbn.packageName.lowercase(Locale.ROOT)
        if (packageName !in DISCORD_PACKAGES) {
            return false
        }
        val source = sbn.notification
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            source.channelId?.trim().orEmpty()
        } else {
            ""
        }
        val hasVoiceChannel = channelId.equals("mediaConnections", ignoreCase = true)
        val hasVoiceText = contentTexts.any(discordVoiceConnectedPattern::containsMatchIn)
        val hasDisconnectAction = actionTexts.any(callEndActionPattern::containsMatchIn)
        return (hasVoiceChannel || hasVoiceText) && (hasVoiceText || hasDisconnectAction)
    }

    private fun upsertCallMirrorState(
        context: Context,
        sbn: StatusBarNotification,
        appPresentationOverride: AppPresentationOverride,
        snapshot: CallMirrorSnapshot
    ): Long {
        val now = System.currentTimeMillis()
        var scheduleGeneration: Long? = null
        val startedAtWallClockMs = synchronized(stateLock) {
            val existing = callMirrorStates[sbn.key]
            val resolvedStart = resolveCallStartedAtWallClockMs(
                sbn = sbn,
                snapshot = snapshot,
                existingStartedAtWallClockMs = existing?.startedAtWallClockMs,
                nowWallClockMs = now
            )
            if (existing == null) {
                callMirrorGenerationCounter += 1L
                val generation = callMirrorGenerationCounter
                callMirrorStates[sbn.key] = CallMirrorState(
                    sbn = sbn,
                    appPresentationOverride = appPresentationOverride,
                    startedAtWallClockMs = resolvedStart,
                    generation = generation
                )
                scheduleGeneration = generation
            } else {
                existing.sbn = sbn
                existing.appPresentationOverride = appPresentationOverride
                existing.startedAtWallClockMs = resolvedStart
            }
            callMirrorStates[sbn.key]?.startedAtWallClockMs ?: resolvedStart
        }

        scheduleGeneration?.let { generation ->
            scheduleCallMirrorRefresh(
                context = context.applicationContext,
                mirrorKey = sbn.key,
                generation = generation
            )
        }
        return startedAtWallClockMs
    }

    private fun scheduleCallMirrorRefresh(
        context: Context,
        mirrorKey: String,
        generation: Long
    ) {
        mainHandler.postDelayed({
            val frame = synchronized(stateLock) {
                val state = callMirrorStates[mirrorKey] ?: return@synchronized null
                if (state.generation != generation || isUserDismissedMirrorLocked(mirrorKey)) {
                    if (state.generation == generation) {
                        callMirrorStates.remove(mirrorKey)
                    }
                    return@synchronized null
                }
                CallMirrorFrame(
                    sbn = state.sbn,
                    appPresentationOverride = state.appPresentationOverride,
                    startedAtWallClockMs = state.startedAtWallClockMs
                )
            } ?: return@postDelayed

            val manager = NotificationManagerCompat.from(context)
            try {
                val notification = buildMirroredNotification(
                    context = context,
                    sbn = frame.sbn,
                    appPresentationOverride = frame.appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.CALLS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    requestPromoted = true,
                    allowNavigationIconHeuristics = false,
                    callMirrorActive = true,
                    callChronometerStartWallClockMs = frame.startedAtWallClockMs
                )
                notifyWithPromotionFallback(
                    context = context,
                    manager = manager,
                    notificationId = mirrorIdForKey(mirrorKey),
                    mirrorKey = mirrorKey,
                    promotedNotification = notification,
                    sbn = frame.sbn,
                    appPresentationOverride = frame.appPresentationOverride,
                    mirrorChannel = MirrorNotificationChannel.CALLS,
                    progressOverride = null,
                    otpOverride = null,
                    smartShortTextOverride = null,
                    allowNavigationIconHeuristics = false,
                    callMirrorActive = true,
                    callChronometerStartWallClockMs = frame.startedAtWallClockMs
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Failed call duration mirror update: $mirrorKey", error)
            }

            if (isCallMirrorGenerationCurrent(mirrorKey, generation)) {
                scheduleCallMirrorRefresh(
                    context = context,
                    mirrorKey = mirrorKey,
                    generation = generation
                )
            }
        }, CALL_DURATION_REFRESH_MS)
    }

    private fun isCallMirrorGenerationCurrent(mirrorKey: String, generation: Long): Boolean {
        return synchronized(stateLock) {
            val state = callMirrorStates[mirrorKey] ?: return@synchronized false
            state.generation == generation && !isUserDismissedMirrorLocked(mirrorKey)
        }
    }

    private fun resolveCallStartedAtWallClockMs(
        sbn: StatusBarNotification,
        snapshot: CallMirrorSnapshot,
        existingStartedAtWallClockMs: Long?,
        nowWallClockMs: Long
    ): Long {
        val resolved = when {
            snapshot.explicitStartWallClockMs != null -> snapshot.explicitStartWallClockMs
            snapshot.elapsedDurationMs != null -> nowWallClockMs - snapshot.elapsedDurationMs
            existingStartedAtWallClockMs != null -> existingStartedAtWallClockMs
            sbn.postTime > 0L -> sbn.postTime
            else -> nowWallClockMs
        }
        return resolved.coerceIn(0L, nowWallClockMs)
    }

    private fun resolveCallTimeSeed(
        notification: Notification,
        contentTexts: List<String>
    ): CallTimeSeed {
        resolveCallChronometerStartWallClockMs(notification)?.let { startMs ->
            return CallTimeSeed(
                explicitStartWallClockMs = startMs,
                elapsedDurationMs = null
            )
        }

        val parsedDurationMs = contentTexts
            .asSequence()
            .flatMap { text -> callDurationPattern.findAll(text).map { it.value } }
            .mapNotNull(::parseClockDurationMs)
            .maxOrNull()

        return CallTimeSeed(
            explicitStartWallClockMs = null,
            elapsedDurationMs = parsedDurationMs
        )
    }

    private fun resolveCallChronometerStartWallClockMs(notification: Notification): Long? {
        val extras = notification.extras
        if (!extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) {
            return null
        }
        if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)) {
            return null
        }
        return notification.`when`.takeIf { it > 0L }
    }

    private fun parseClockDurationMs(value: String): Long? {
        val parts = value.split(":")
        if (parts.size !in 2..3) {
            return null
        }
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        val totalSeconds = if (numbers.size == 3) {
            val hours = numbers[0]
            val minutes = numbers[1]
            val seconds = numbers[2]
            if (minutes !in 0..59 || seconds !in 0..59) {
                return null
            }
            hours * 3_600L + minutes * 60L + seconds
        } else {
            val minutes = numbers[0]
            val seconds = numbers[1]
            if (seconds !in 0..59) {
                return null
            }
            minutes * 60L + seconds
        }
        return (totalSeconds * 1_000L).coerceAtLeast(0L)
    }

    private fun hasIncomingOrDialingCallMarker(
        contentTexts: List<String>,
        actionTexts: List<String>
    ): Boolean {
        if (actionTexts.any(callAnswerActionPattern::containsMatchIn)) {
            return true
        }
        return contentTexts.any { text ->
            callIncomingTextPattern.containsMatchIn(text) ||
                    callDialingTextPattern.containsMatchIn(text)
        }
    }

    private fun collectCallActionTexts(notification: Notification): List<String> {
        return notification.actions
            ?.mapNotNull { action -> normalizeNotificationText(action.title) }
            ?.distinct()
            .orEmpty()
    }

    private fun collectCallContentTexts(
        notification: Notification,
        fallbackTitle: String
    ): List<String> {
        val extras = notification.extras
        val parts = mutableListOf<String>()

        fun add(value: CharSequence?) {
            normalizeNotificationText(value)?.let(parts::add)
        }

        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        add(notification.tickerText)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach(::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.let(Notification.MessagingStyle.Message::getMessagesFromBundleArray)
                ?.forEach { message -> add(message.text) }
        }
        extractRemoteViewTexts(notification).forEach { text -> add(text) }

        if (parts.isEmpty()) {
            parts.add(fallbackTitle)
        }
        return parts.distinct()
    }

    private fun normalizeNotificationText(value: CharSequence?): String? {
        return value
            ?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun cancelMirrored(context: Context, sbn: StatusBarNotification) {
        try {
            val manager = NotificationManagerCompat.from(context)
            val staleAggregateIds = synchronized(stateLock) {
                val directMirrorId = mirrorIdForKey(sbn.key)
                userDismissedMirrorKeys.remove(sbn.key)
                callMirrorStates.remove(sbn.key)
                mirrorKeysByNotificationId.remove(directMirrorId)
                clearAggregateTrackingForSbnKeyLocked(sbn.key)
            }
            staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
            cancelMirroredNotification(manager, mirrorIdForKey(sbn.key))
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to cancel mirrored notification: ${sbn.key}", error)
        }
    }

    private fun cancelMirrorsForIgnoredSource(
        manager: NotificationManagerCompat,
        sbn: StatusBarNotification
    ) {
        val directMirrorId = mirrorIdForKey(sbn.key)
        val staleAggregateIds = synchronized(stateLock) {
            userDismissedMirrorKeys.remove(sbn.key)
            callMirrorStates.remove(sbn.key)
            mirrorKeysByNotificationId.remove(directMirrorId)
            clearAggregateTrackingForSbnKeyLocked(sbn.key)
        }
        staleAggregateIds.forEach { cancelMirroredNotification(manager, it) }
        cancelMirroredNotification(manager, directMirrorId)
    }

    fun handleMirroredRemoved(context: Context, sbn: StatusBarNotification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isMirrorNotificationChannel(sbn.notification.channelId)
        ) {
            return
        }
        if (ConverterPrefs(context).getPreventMirrorDismissEnabled()) {
            return
        }

        synchronized(stateLock) {
            val now = SystemClock.elapsedRealtime()
            pruneProgrammaticMirrorCancelsLocked(now)
            if (consumeProgrammaticMirrorCancelLocked(sbn.id, now)) {
                return
            }

            val mirrorKey = mirrorKeysByNotificationId.remove(sbn.id) ?: return
            userDismissedMirrorKeys.add(mirrorKey)
            callMirrorStates.remove(mirrorKey)
            smartAnimationGenerations.remove(mirrorKey)
            smartAnimationStates.remove(mirrorKey)
            otpAnimationGenerations.remove(mirrorKey)
        }
    }

    private fun notMirroredResult(): MirrorResult {
        return MirrorResult(mirrored = false)
    }

    private fun mirroredResult(dedupKind: MirrorDedupKind = MirrorDedupKind.NONE): MirrorResult {
        return MirrorResult(
            mirrored = true,
            dedupKind = dedupKind
        )
    }

    private fun isMirrorNotificationChannel(channelId: String?): Boolean {
        val normalized = channelId?.trim().orEmpty()
        return normalized.isNotEmpty() &&
                MirrorNotificationChannel.entries.any { it.id == normalized }
    }

    private fun mirrorChannelForSmartRule(ruleId: String?): MirrorNotificationChannel {
        return when (ruleId) {
            "vpn", "external_device" -> MirrorNotificationChannel.NETWORK_CONNECTIONS
            "navigation", "weather" -> MirrorNotificationChannel.MISCELLANEOUS
            else -> MirrorNotificationChannel.SMART_CONVERSIONS
        }
    }

    private fun mirrorChannelText(
        context: Context,
        channel: MirrorNotificationChannel
    ): MirrorChannelText {
        val isRussian = isRussianLocale(context)
        return when (channel) {
            MirrorNotificationChannel.LEGACY -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "LiveBridge",
                        description = "Старый общий канал конвертированных уведомлений"
                    )
                } else {
                    MirrorChannelText(
                        name = CHANNEL_NAME,
                        description = "Legacy channel for converted notifications"
                    )
                }
            }

            MirrorNotificationChannel.PROGRESS_NOTIFICATIONS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Progress notifications",
                        description = "Конвертированные уведомления с прогрессом"
                    )
                } else {
                    MirrorChannelText(
                        name = "Progress notifications",
                        description = "Converted notifications with progress"
                    )
                }
            }

            MirrorNotificationChannel.OTP_CODES -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "OTP codes",
                        description = "Коды подтверждения и действия с ними"
                    )
                } else {
                    MirrorChannelText(
                        name = "OTP codes",
                        description = "Verification code conversions"
                    )
                }
            }

            MirrorNotificationChannel.SMART_CONVERSIONS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Smart conversions",
                        description = "Такси, доставки и похожие smart-конверсии"
                    )
                } else {
                    MirrorChannelText(
                        name = "Smart conversions",
                        description = "Taxi, deliveries and similar smart conversions"
                    )
                }
            }

            MirrorNotificationChannel.MEDIA_PLAYBACK -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Media playback",
                        description = "Конвертированный медиаплеер"
                    )
                } else {
                    MirrorChannelText(
                        name = "Media playback",
                        description = "Converted media playback notifications"
                    )
                }
            }

            MirrorNotificationChannel.CALLS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Calls",
                        description = "\u0410\u043a\u0442\u0438\u0432\u043d\u044b\u0435 \u0437\u0432\u043e\u043d\u043a\u0438 \u0441 \u0442\u0430\u0439\u043c\u0435\u0440\u043e\u043c"
                    )
                } else {
                    MirrorChannelText(
                        name = "Calls",
                        description = "Active calls with elapsed call time"
                    )
                }
            }

            MirrorNotificationChannel.NETWORK_CONNECTIONS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Network & connections",
                        description = "VPN и внешние устройства"
                    )
                } else {
                    MirrorChannelText(
                        name = "Network & connections",
                        description = "VPN and external device conversions"
                    )
                }
            }

            MirrorNotificationChannel.MISCELLANEOUS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Miscellaneous conversions",
                        description = "Навигация, погода и прочие конверсии"
                    )
                } else {
                    MirrorChannelText(
                        name = "Miscellaneous conversions",
                        description = "Navigation, weather and other conversions"
                    )
                }
            }

            MirrorNotificationChannel.BYPASS -> {
                if (isRussian) {
                    MirrorChannelText(
                        name = "Bypass applications",
                        description = "Уведомления приложений из bypass-списка"
                    )
                } else {
                    MirrorChannelText(
                        name = "Bypass applications",
                        description = "Notifications from bypassed apps"
                    )
                }
            }
        }
    }

    private fun passesBaseFilters(
        prefs: ConverterPrefs,
        sbn: StatusBarNotification,
        parserDictionary: LiveParserDictionary,
        mediaPlaybackSmartEnabled: Boolean
    ): Boolean {
        val source = sbn.notification

        if (isLikelyMediaPlaybackNotification(source) && !mediaPlaybackSmartEnabled) {
            return false
        }

        val packageNameLower = sbn.packageName.lowercase(Locale.ROOT)
        if (parserDictionary.blockedSourcePackages.contains(packageNameLower) &&
            packageNameLower != TWO_GIS_PACKAGE
        ) {
            return false
        }

        return prefs.isPackageAllowed(sbn.packageName)
    }

    private fun passesCoreFilters(
        appPackageName: String,
        sbn: StatusBarNotification
    ): Boolean {
        val packageNameLower = sbn.packageName.lowercase(Locale.ROOT)
        if (appPackageName.isNotEmpty() && sbn.packageName == appPackageName) {
            return false
        }
        val source = sbn.notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isMirrorNotificationChannel(source.channelId)
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 36 && source.flags and 0x40000 != 0) {
            return false
        }
        if (source.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
            packageNameLower != TWO_GIS_PACKAGE
        ) {
            return false
        }
        return true
    }

    private fun isPrivacyRedactedNotification(
        source: Notification,
        parserDictionary: LiveParserDictionary
    ): Boolean {
        val contentTexts = collectNotificationContentTexts(source)
        if (contentTexts.isEmpty()) {
            return false
        }
        val placeholders = parserDictionary.privacyRedactionPlaceholders
            .ifEmpty { FALLBACK_PRIVACY_REDACTION_PLACEHOLDERS }

        return contentTexts.any { text ->
            isPrivacyRedactionPlaceholder(text, placeholders)
        }
    }

    private fun collectNotificationContentTexts(source: Notification): List<String> {
        val extras = source.extras
        val parts = mutableListOf<String>()

        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                parts.add(text)
            }
        }

        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach(::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.let(Notification.MessagingStyle.Message::getMessagesFromBundleArray)
                ?.mapNotNull { it.text }
                ?.forEach(::add)
        }

        return parts.distinct()
    }

    private fun isPrivacyRedactionPlaceholder(text: String, placeholders: Set<String>): Boolean {
        val normalized = text
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        return placeholders.any { placeholder ->
            val normalizedPlaceholder = placeholder
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
            normalizedPlaceholder.isNotBlank() &&
                    (normalized == normalizedPlaceholder ||
                            normalized.contains(normalizedPlaceholder))
        }
    }

    private fun buildMirroredNotification(
        context: Context,
        sbn: StatusBarNotification,
        appPresentationOverride: AppPresentationOverride,
        mirrorChannel: MirrorNotificationChannel,
        progressOverride: ProgressOverride?,
        otpOverride: OtpMatch?,
        smartShortTextOverride: String?,
        smartRuleId: String? = null,
        requestPromoted: Boolean,
        otpShortTextOverride: String? = null,
        allowNavigationIconHeuristics: Boolean = true,
        preferMediaControls: Boolean = false,
        mediaPlaybackIsPlaying: Boolean? = null,
        titleOverride: String? = null,
        textOverride: String? = null,
        largeIconOverride: Bitmap? = null,
        callMirrorActive: Boolean = false,
        callChronometerStartWallClockMs: Long? = null
    ): Notification {
        val runtimePrefs = ConverterPrefs(context)
        val parserDictionary = LiveParserDictionaryLoader.get(context, runtimePrefs)
        val source = sbn.notification
        val sourcePackageNameLower = sbn.packageName.lowercase(Locale.ROOT)
        val isTwoGisPackage = sourcePackageNameLower == TWO_GIS_PACKAGE
        val sourceSmallIcon = resolveSourceSmallIcon(context, sbn)
        val appSmallIcon = resolveAppSmallIcon(context, sbn.packageName)
        val shouldTryNavigationArrowIcon =
            (appPresentationOverride.iconSource == NotificationIconSource.NOTIFICATION ||
                    isTwoGisPackage) &&
                    (smartRuleId == "navigation" ||
                            isTwoGisPackage ||
                            (allowNavigationIconHeuristics &&
                                    isLikelyNavigationPackage(sbn.packageName, parserDictionary)))
        val navigationDrawable =
            if (shouldTryNavigationArrowIcon) {
                resolveRemoteDrawableAssets(context, sbn)
            } else {
                null
            }
        val sourceLargeIcon = resolveSourceLargeIconBitmap(context, source)?.let { bitmap ->
            // Weather providers routinely flatten their condition icon onto a solid white
            // card, which shows up as an ugly white box on our dark mirrored card. Only clean
            // that up for the weather conversion path; every other source app's icon is left
            // byte-for-byte as provided.
            if (smartRuleId == "weather") stripWhiteIconBackground(bitmap) else bitmap
        }
        val preferredLargeIcon = largeIconOverride ?: if (shouldTryNavigationArrowIcon) {
            navigationDrawable?.bitmap ?: sourceLargeIcon
        } else {
            sourceLargeIcon
        }

        val appName = resolveAppName(context, sbn.packageName)
        val allowRemoteViewTextFallback = shouldTryNavigationArrowIcon
        val title = titleOverride?.takeIf { it.isNotBlank() }
            ?: extractTitle(source, appName, allowRemoteViewTextFallback)
        val text = textOverride?.takeIf { it.isNotBlank() }
            ?: extractText(source, allowRemoteViewTextFallback)
        val configuredDisplayTitle = if (appPresentationOverride.usesExplicitSources()) {
            when (appPresentationOverride.resolvedTitleSource()) {
                NotificationTitleSource.NOTIFICATION_TITLE -> title.ifBlank { appName }
                NotificationTitleSource.APP_TITLE -> appName.ifBlank { title }
            }
        } else {
            when (appPresentationOverride.compactTextSource) {
                CompactTextSource.TEXT -> text.ifBlank { title }
                CompactTextSource.TITLE -> title
            }
        }
        val configuredDisplayText = if (appPresentationOverride.usesExplicitSources()) {
            when (appPresentationOverride.resolvedContentSource()) {
                NotificationContentSource.NOTIFICATION_TEXT -> text.ifBlank { title }
                NotificationContentSource.NOTIFICATION_TITLE -> title.ifBlank { text }
            }
        } else if (
            appPresentationOverride.compactTextSource == CompactTextSource.TEXT &&
            title.isNotBlank() &&
            title != configuredDisplayTitle
        ) {
            title
        } else {
            text
        }
        val displayTitle = if (preferMediaControls) {
            title.takeIfMeaningfulMediaPlaybackText()
                ?: configuredDisplayTitle.takeIfMeaningfulMediaPlaybackText()
                ?: appName
        } else {
            configuredDisplayTitle
        }
        val displayText = if (preferMediaControls) {
            text.takeIfMeaningfulMediaPlaybackText()
                ?: configuredDisplayText.takeIfMeaningfulMediaPlaybackText()
                ?: ""
        } else {
            configuredDisplayText
        }
        val callMirrorBodyText = if (callMirrorActive) {
            resolveCallMirrorBodyText(
                notification = source,
                displayTitle = displayTitle,
                displayText = displayText
            )
        } else {
            null
        }
        val otpPresentationText = otpShortTextOverride ?: otpOverride?.code
        val contentTitle = otpPresentationText ?: displayTitle
        val contentText = if (otpOverride != null) {
            appName
        } else if (callMirrorBodyText != null) {
            callMirrorBodyText
        } else {
            displayText
        }
        val hideLockscreenContent = runtimePrefs.getHideLockscreenContentEnabled()
        val visibility = when {
            preferMediaControls &&
                    !runtimePrefs.getSmartMediaPlaybackShowOnLockScreen() ->
                NotificationCompat.VISIBILITY_SECRET

            hideLockscreenContent -> NotificationCompat.VISIBILITY_PRIVATE
            else -> NotificationCompat.VISIBILITY_PUBLIC
        }
        val useMediaActionSymbols = preferMediaControls &&
                runtimePrefs.getSmartMediaPlaybackUseSymbolsInPlayer()
        val aospCuttingEnabled = runtimePrefs.getAospCuttingEnabled()
        val aospCuttingLength = runtimePrefs.getAospCuttingLength()
        val hyperBridgeEnabled = runtimePrefs.getHyperBridgeEnabled()
        val callChronometerStart = callChronometerStartWallClockMs
            ?.takeIf { callMirrorActive && it > 0L }
            ?.coerceAtMost(System.currentTimeMillis())

        val progressMax = progressOverride?.max ?: source.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressValue = progressOverride?.value ?: source.extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = if (progressOverride != null) {
            false
        } else {
            source.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        }
        val hasProgress = progressOverride != null ||
                hasEffectiveProgress(sbn.packageName, source)
        val determinateProgressPercent = if (hasProgress && !indeterminate && progressMax > 0) {
            val safeMax = progressMax.coerceAtLeast(1)
            val safeProgress = progressValue.coerceIn(0, safeMax)
            ((safeProgress.toFloat() / safeMax.toFloat()) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            null
        }

        val builder = NotificationCompat.Builder(context, mirrorChannel.id)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText(appName)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(callChronometerStart ?: resolveStableWhen(source, sbn.postTime))
            .setShowWhen(callChronometerStart != null)
            .setColor(progressColor)
            .setCategory(
                if (callMirrorActive) {
                    Notification.CATEGORY_CALL
                } else if (hasProgress) {
                    Notification.CATEGORY_PROGRESS
                } else {
                    Notification.CATEGORY_STATUS
                }
            )
            .setVisibility(visibility)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (callChronometerStart != null) {
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(false)
        }

        val preferredSmallIcon = when (appPresentationOverride.iconSource) {
            NotificationIconSource.NOTIFICATION ->
                navigationDrawable?.icon ?: sourceSmallIcon ?: appSmallIcon

            NotificationIconSource.APP ->
                if (isTwoGisPackage) {
                    navigationDrawable?.icon ?: appSmallIcon ?: sourceSmallIcon
                } else {
                    appSmallIcon ?: sourceSmallIcon
                }
        }
        applySmallIcon(context, builder, preferredSmallIcon)
        preferredLargeIcon?.let(builder::setLargeIcon)

        if (hideLockscreenContent && visibility == NotificationCompat.VISIBILITY_PRIVATE) {
            val publicBuilder = NotificationCompat.Builder(context, mirrorChannel.id)
                .setContentTitle(LOCKSCREEN_CONTENT_HIDDEN_TEXT)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setDefaults(0)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(resolveStableWhen(source, sbn.postTime))
                .setShowWhen(false)
                .setColor(progressColor)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            applySmallIcon(context, publicBuilder, preferredSmallIcon)
            builder.setPublicVersion(publicBuilder.build())
        }

        if (requestPromoted) {
            builder.setRequestPromotedOngoing(true)
        }

        if (otpOverride != null) {
            builder.addAction(buildCopyOtpAction(context, sbn, otpOverride.code))
        }

        source.contentIntent?.let(builder::setContentIntent)
        copySourceActions(
            source = source,
            builder = builder,
            maxActions = if (otpOverride != null) {
                MAX_MIRRORED_ACTIONS - 1
            } else {
                MAX_MIRRORED_ACTIONS
            },
            preferMediaControls = preferMediaControls,
            mediaPlaybackIsPlaying = mediaPlaybackIsPlaying,
            useMediaActionSymbols = useMediaActionSymbols
        )

        if (hasProgress) {
            if (indeterminate || progressMax <= 0) {
                builder.setProgress(0, 0, true)
                builder.setStyle(
                    NotificationCompat.ProgressStyle()
                        .setProgressIndeterminate(true)
                        .setStyledByProgress(true)
                )
            } else {
                val safeMax = progressMax.coerceAtLeast(1)
                val safeProgress = progressValue.coerceIn(0, safeMax)
                val percent = determinateProgressPercent ?: 0

                builder.setProgress(safeMax, safeProgress, false)
                builder.setStyle(
                    NotificationCompat.ProgressStyle()
                        .setProgress(percent)
                        .setStyledByProgress(true)
                )
                val progressShortText = if (preferMediaControls) {
                    smartShortTextOverride.takeIfMeaningfulMediaPlaybackText()
                        ?: displayTitle.takeIfMeaningfulMediaPlaybackText()
                        ?: displayText.takeIfMeaningfulMediaPlaybackText()
                        ?: appName
                } else {
                    smartShortTextOverride ?: "$percent%"
                }
                builder.setShortCriticalText(
                    limitIslandText(
                        progressShortText,
                        aospCuttingEnabled,
                        aospCuttingLength
                    )
                )
            }
        } else if (otpOverride != null) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(contentTitle)
                    .bigText(text)
            )
            builder.setShortCriticalText(
                limitIslandText(
                    otpPresentationText ?: otpOverride.code,
                    aospCuttingEnabled,
                    aospCuttingLength
                )
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(callMirrorBodyText ?: text))
        }
        if (callChronometerStart != null && !hasProgress) {
            builder.setShortCriticalText(
                limitIslandText(
                    formatMillisecondsAsClock(System.currentTimeMillis() - callChronometerStart),
                    aospCuttingEnabled,
                    aospCuttingLength
                )
            )
        }
        if (smartShortTextOverride != null && !hasProgress) {
            builder.setContentText(smartShortTextOverride)
            builder.setShortCriticalText(
                limitIslandText(
                    smartShortTextOverride,
                    aospCuttingEnabled,
                    aospCuttingLength
                )
            )
        }

        if (hyperBridgeEnabled) {
            val mediaTicker = if (preferMediaControls) {
                smartShortTextOverride.takeIfMeaningfulMediaPlaybackText()
                    ?: displayTitle.takeIfMeaningfulMediaPlaybackText()
                    ?: displayText.takeIfMeaningfulMediaPlaybackText()
                    ?: appName
            } else {
                null
            }
            val hyperTicker = when {
                otpOverride != null -> otpPresentationText ?: otpOverride.code
                callChronometerStart != null ->
                    formatMillisecondsAsClock(System.currentTimeMillis() - callChronometerStart)
                mediaTicker != null -> mediaTicker
                !smartShortTextOverride.isNullOrBlank() -> smartShortTextOverride
                determinateProgressPercent != null -> "$determinateProgressPercent%"
                else -> displayTitle
            }
            HyperBridgeAdapter.apply(
                context = context,
                builder = builder,
                sourcePackageName = sbn.packageName,
                appName = appName,
                title = contentTitle,
                content = contentText,
                ticker = hyperTicker,
                progressPercent = determinateProgressPercent,
                largeIcon = preferredLargeIcon,
                fallbackSmallIcon = preferredSmallIcon,
                sourceActions = source.actions
            )
        }

        return builder.build()
    }

    private fun notifyWithPromotionFallback(
        context: Context,
        manager: NotificationManagerCompat,
        notificationId: Int,
        mirrorKey: String,
        promotedNotification: Notification,
        sbn: StatusBarNotification,
        appPresentationOverride: AppPresentationOverride,
        mirrorChannel: MirrorNotificationChannel,
        progressOverride: ProgressOverride?,
        otpOverride: OtpMatch?,
        smartShortTextOverride: String?,
        smartRuleId: String? = null,
        otpShortTextOverride: String? = null,
        allowNavigationIconHeuristics: Boolean = true,
        preferMediaControls: Boolean = false,
        mediaPlaybackIsPlaying: Boolean? = null,
        titleOverride: String? = null,
        textOverride: String? = null,
        largeIconOverride: Bitmap? = null,
        callMirrorActive: Boolean = false,
        callChronometerStartWallClockMs: Long? = null
    ) {
        try {
            notifyMirroredNotification(
                manager = manager,
                notificationId = notificationId,
                notification = promotedNotification,
                mirrorKey = mirrorKey
            )
        } catch (error: Throwable) {
            val fallback = buildMirroredNotification(
                context = context,
                sbn = sbn,
                appPresentationOverride = appPresentationOverride,
                mirrorChannel = mirrorChannel,
                progressOverride = progressOverride,
                otpOverride = otpOverride,
                smartShortTextOverride = smartShortTextOverride,
                smartRuleId = smartRuleId,
                requestPromoted = false,
                otpShortTextOverride = otpShortTextOverride,
                allowNavigationIconHeuristics = allowNavigationIconHeuristics,
                preferMediaControls = preferMediaControls,
                mediaPlaybackIsPlaying = mediaPlaybackIsPlaying,
                titleOverride = titleOverride,
                textOverride = textOverride,
                largeIconOverride = largeIconOverride,
                callMirrorActive = callMirrorActive,
                callChronometerStartWallClockMs = callChronometerStartWallClockMs
            )
            notifyMirroredNotification(
                manager = manager,
                notificationId = notificationId,
                notification = fallback,
                mirrorKey = mirrorKey
            )
        }
    }

    private fun detectSmartStage(
        packageName: String,
        source: Notification,
        parserDictionary: LiveParserDictionary,
        taxiEnabled: Boolean,
        deliveryEnabled: Boolean,
        navigationEnabled: Boolean,
        weatherEnabled: Boolean,
        externalDevicesEnabled: Boolean,
        externalDevicesIgnoreDebugging: Boolean,
        vpnEnabled: Boolean,
        smartPackageAllowed: Boolean,
        hasNativeProgress: Boolean
    ): SmartStageMatch? {
        val isNavigationPackage = isLikelyNavigationPackage(packageName, parserDictionary)
        val packageLower = packageName.lowercase(Locale.ROOT)
        val isWeatherPackage = isLikelyWeatherPackage(packageLower, parserDictionary)
        val isExternalDevicePackage = isLikelySmartRulePackage(
            packageNameLower = packageLower,
            ruleId = "external_device",
            parserDictionary = parserDictionary
        )
        val isVpnPackage = isLikelyVpnPackage(
            packageNameLower = packageLower,
            parserDictionary = parserDictionary
        )
        val isFoodDeliveryPackage = isLikelySmartRulePackage(
            packageNameLower = packageLower,
            ruleId = "food",
            parserDictionary = parserDictionary
        )
        val combinedText = collectNotificationText(
            notification = source,
            fallbackTitle = packageName,
            includeRemoteViewTexts = isNavigationPackage ||
                    isFoodDeliveryPackage ||
                    isWeatherPackage ||
                    isExternalDevicePackage ||
                    isVpnPackage
        ).lowercase(Locale.ROOT)

        for (rule in parserDictionary.smartRules) {
            if (hasNativeProgress && rule.id != "weather") {
                continue
            }
            if (rule.id == "taxi" && (!taxiEnabled || !smartPackageAllowed)) {
                continue
            }
            if (rule.id == "food" && (!deliveryEnabled || !smartPackageAllowed)) {
                continue
            }
            if (rule.id == "navigation" && !navigationEnabled) {
                continue
            }
            if (rule.id == "weather" && !weatherEnabled) {
                continue
            }
            if (rule.id == "external_device" && !externalDevicesEnabled) {
                continue
            }
            if (rule.id == "external_device" &&
                externalDevicesIgnoreDebugging &&
                isExternalDeviceDebuggingNotification(combinedText)
            ) {
                continue
            }
            if (rule.id == "vpn" && !vpnEnabled) {
                continue
            }
            if (rule.id == "vpn" && !hasVpnSpeedPattern(combinedText, parserDictionary)) {
                continue
            }
            if (!rule.isRelevant(packageLower, combinedText)) {
                continue
            }
            if (rule.isExcluded(combinedText)) {
                continue
            }
            if (rule.id == "external_device" &&
                extractConnectedDeviceName(
                    text = combinedText,
                    parserDictionary = parserDictionary
                ).isNullOrBlank()
            ) {
                continue
            }

            val matchedSignal = rule.signals.firstOrNull { it.pattern.containsMatchIn(combinedText) } ?: continue
            val entityToken = when (rule.id) {
                "navigation" -> "route"
                "weather" -> "weather"
                "external_device" -> "device"
                "vpn" -> "vpn"
                else -> extractEntityToken(combinedText, parserDictionary)
            }
            val compactOrderCode = if (rule.id == "food") {
                extractCompactOrderCode(entityToken)
                    ?.takeIf { isExplicitOrderEntityToken(combinedText, entityToken) }
            } else {
                null
            }
            val aggregateEntityToken = when {
                rule.id == "food" && compactOrderCode == null -> FOOD_DELIVERY_AGGREGATE_ENTITY
                rule.id == "food" -> compactOrderCode
                else -> entityToken
            }

            return SmartStageMatch(
                aggregateKey = "$packageLower:${rule.id}:$aggregateEntityToken",
                stageValue = matchedSignal.stage,
                maxStage = rule.maxStage,
                compactOrderCode = compactOrderCode,
                keepHighestStage = rule.id != "navigation" &&
                        rule.id != "weather" &&
                        rule.id != "external_device" &&
                        rule.id != "vpn"
            )
        }

        if (weatherEnabled) {
            detectWeatherSmartStage(
                packageNameLower = packageLower,
                source = source,
                parserDictionary = parserDictionary
            )?.let { return it }
        }

        if (vpnEnabled) {
            detectVpnTrafficSmartStage(
                packageNameLower = packageLower,
                source = source,
                parserDictionary = parserDictionary
            )?.let { return it }
        }

        return null
    }

    private fun isNotificationDedupEligibleSmartRule(ruleId: String): Boolean {
        return ruleId != "navigation" &&
                ruleId != "weather" &&
                ruleId != "external_device" &&
                ruleId != "vpn"
    }

    private fun isExternalDeviceDebuggingNotification(text: String): Boolean {
        return externalDeviceDebuggingPattern.containsMatchIn(text)
    }

    private fun detectWeatherSmartStage(
        packageNameLower: String,
        source: Notification,
        parserDictionary: LiveParserDictionary
    ): SmartStageMatch? {
        val combinedText = collectNotificationText(
            notification = source,
            fallbackTitle = packageNameLower,
            includeRemoteViewTexts = true
        )
        if (combinedText.isBlank()) {
            return null
        }
        val likelyWeatherPackage = isLikelyWeatherPackage(packageNameLower, parserDictionary)
        val hasWeatherContext = parserDictionary.weatherContextPattern.containsMatchIn(combinedText)
        if (!likelyWeatherPackage && !hasWeatherContext) {
            return null
        }

        val temperature = extractWeatherTemperatureFromText(combinedText, parserDictionary) ?: return null
        if (temperature.isBlank()) {
            return null
        }

        return SmartStageMatch(
            aggregateKey = "$packageNameLower:weather:weather",
            stageValue = 1,
            maxStage = 1,
            compactOrderCode = null,
            keepHighestStage = false
        )
    }

    private fun detectVpnTrafficSmartStage(
        packageNameLower: String,
        source: Notification,
        parserDictionary: LiveParserDictionary
    ): SmartStageMatch? {
        val combinedText = collectNotificationText(
            notification = source,
            fallbackTitle = packageNameLower,
            includeRemoteViewTexts = true
        )
        if (combinedText.isBlank()) {
            return null
        }
        if (!hasVpnSpeedPattern(combinedText, parserDictionary)) {
            return null
        }

        val likelyVpnPackage = isLikelyVpnPackage(packageNameLower, parserDictionary)
        val hasVpnContext = parserDictionary.vpnContextPattern.containsMatchIn(combinedText)
        if (!likelyVpnPackage && !hasVpnContext) {
            return null
        }

        return SmartStageMatch(
            aggregateKey = "$packageNameLower:vpn:vpn",
            stageValue = 1,
            maxStage = 1,
            compactOrderCode = null,
            keepHighestStage = false
        )
    }

    private fun detectTextProgress(
        packageName: String,
        source: Notification,
        parserDictionary: LiveParserDictionary
    ): TextProgressMatch? {
        if (isLikelyNavigationPackage(packageName, parserDictionary)) {
            return null
        }

        val combinedText = collectNotificationText(
            notification = source,
            fallbackTitle = packageName,
            includeRemoteViewTexts = true
        )
        if (combinedText.isBlank()) {
            return null
        }

        val percentPattern = parserDictionary.textProgressPercentPattern
        val combinedLower = combinedText.lowercase(Locale.ROOT)
        val matches = percentPattern.findAll(combinedText)
        for (match in matches) {
            val percentValue = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (percentValue !in 0..100) {
                continue
            }
            if (!hasTextProgressContextHint(
                    textLower = combinedLower,
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    parserDictionary = parserDictionary
                )
            ) {
                continue
            }
            if (isExcludedTextProgressContext(
                    textLower = combinedLower,
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    parserDictionary = parserDictionary
                )
            ) {
                continue
            }
            return TextProgressMatch(
                percent = percentValue,
                shortText = "$percentValue%"
            )
        }
        return null
    }

    private fun hasTextProgressContextHint(
        textLower: String,
        start: Int,
        endExclusive: Int,
        parserDictionary: LiveParserDictionary
    ): Boolean {
        val contextWindow = parserDictionary.textProgressContextWindow
        val windowStart = (start - contextWindow).coerceAtLeast(0)
        val windowEnd = (endExclusive + contextWindow).coerceAtMost(textLower.length)
        val context = textLower.substring(windowStart, windowEnd)
        return parserDictionary.textProgressIncludeContextPattern.containsMatchIn(context)
    }

    private fun isExcludedTextProgressContext(
        textLower: String,
        start: Int,
        endExclusive: Int,
        parserDictionary: LiveParserDictionary
    ): Boolean {
        val contextWindow = parserDictionary.textProgressContextWindow
        val windowStart = (start - contextWindow).coerceAtLeast(0)
        val windowEnd = (endExclusive + contextWindow).coerceAtMost(textLower.length)
        val context = textLower.substring(windowStart, windowEnd)
        return parserDictionary.textProgressExcludeContextPattern.containsMatchIn(context)
    }

    private fun detectOtpCode(
        packageName: String,
        source: Notification,
        parserDictionary: LiveParserDictionary
    ): OtpMatch? {
        val combinedText = collectNotificationText(
            notification = source,
            fallbackTitle = packageName,
            includeRemoteViewTexts = false
        )
        if (combinedText.isBlank()) {
            return null
        }

        val combinedLower = combinedText.lowercase(Locale.ROOT)
        val hasStrongTrigger = parserDictionary.otpStrongTriggers.any(combinedLower::contains)
        val hasLooseTrigger = parserDictionary.otpLooseTriggerPattern.containsMatchIn(combinedLower)
        if (!hasStrongTrigger && !hasLooseTrigger) {
                   if (!hasStrongTrigger && !hasLooseTrigger) {
            return null
        }

        // Buscar secuencias de dígitos que coincidan con la longitud del código OTP (típico de 4 a 8 dígitos)
        val codeRegex = Regex("""\b\d{${OTP_CODE_LENGTH.first},${OTP_CODE_LENGTH.last}}\b""")
        val matchResult = codeRegex.find(combinedText) ?: return null
        val code = matchResult.value

        // Usar el nombre del paquete como clave de agregación única para la deduplicación de OTP
        val aggregateKey = packageName.lowercase(Locale.ROOT)

        return OtpMatch(
            code = code,
            aggregateKey = aggregateKey
        )
    }
}
