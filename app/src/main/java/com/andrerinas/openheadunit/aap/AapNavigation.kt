package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.cluster.NavigationActivitySignal
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    init { com.andrerinas.openheadunit.hud.BydNavigationOutputs.start(context) }

    private val helper = AapNavigationHelper(context)
    private val snapshot = AapNavigationHelper.NavigationSnapshot()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var isBroadcastScheduled = false
    private var pendingNavEventType = NAV_EVENT_TYPE_TURN

    private val debouncedBroadcastEmitter = Runnable {
        isBroadcastScheduled = false
        helper.sendFullNavigationBroadcast(snapshot, pendingNavEventType)
    }

    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AppLog.d("Nav: Instrument cluster start")
                NavigationActivitySignal.onClusterStart()
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_START)
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AppLog.d("Nav: Instrument cluster stop")
                NavigationActivitySignal.onClusterStop()
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STOP)
                helper.cancelNotification()
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                try {
                    val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    AppLog.d("Nav: Navigation status=${status.status}")
                    // ACTIVE and REROUTING mean a route is live; INACTIVE means a map is on screen
                    // without one. This is the only signal that can also clear the flag.
                    NavigationActivitySignal.onClusterStatus(
                        status.status == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE ||
                            status.status == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING
                    )
                    updateClusterStatus(status)
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATUS)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationClusterStatus", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                    NavigationActivitySignal.onNextTurnDetails(detail.hasNextTurn())
                    snapshot.nextTurnDetail = AapNavigationHelper.TimedMessage(detail, helper.nowElapsedRealtimeMs())
                    val road = detail.road.takeIf { it.isNotBlank() }
                    road?.let {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(it, helper.nowElapsedRealtimeMs())
                    }
                    AppLog.d(
                        "Nav: NextTurnDetail road=${detail.road} " +
                                "hasNextTurn=${detail.hasNextTurn()} nextTurn=${detail.nextTurn}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = null)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                    snapshot.nextTurnDistance = AapNavigationHelper.TimedMessage(event, helper.nowElapsedRealtimeMs())
                    val distanceMeters = event.distanceMeters.takeIf { it >= 0 }
                    NavigationActivitySignal.onNextTurnDistance(distanceMeters ?: -1)
                    AppLog.d(
                        "Nav: NextTurnDistanceEvent hasDistance=${event.hasDistanceMeters()} " +
                                "distance=${event.distanceMeters} hasTime=${event.hasTimeToTurnSeconds()} " +
                                "time=${event.timeToTurnSeconds}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = distanceMeters)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    NavigationActivitySignal.onSteps(state.stepsCount)
                    snapshot.navigationState = AapNavigationHelper.TimedMessage(state, helper.nowElapsedRealtimeMs())
                    val firstStepRoad = state.stepsList.firstOrNull()
                        ?.takeIf { it.hasRoad() && it.road.hasName() }
                        ?.road
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                    if (!firstStepRoad.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(firstStepRoad, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATE)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    NavigationActivitySignal.onPosition()
                    snapshot.currentPosition = AapNavigationHelper.TimedMessage(position, helper.nowElapsedRealtimeMs())
                    val road = position
                        .takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
                        ?.currentRoad
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: snapshot.currentStreet?.payload
                    if (!road.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(road, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_CURRENT_POSITION)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun clearAccumulatedData() {
        snapshot.clusterStatus = null
        snapshot.nextTurnDetail = null
        snapshot.nextTurnDistance = null
        snapshot.navigationState = null
        snapshot.currentPosition = null
        snapshot.currentStreet = null
    }

    private fun clearAccumulatedDataPreservingStatus(
        status: AapNavigationHelper.TimedMessage<NavigationStatus.NavigationClusterStatus>
    ) {
        clearAccumulatedData()
        snapshot.clusterStatus = status
    }

    private fun updateClusterStatus(status: NavigationStatus.NavigationClusterStatus) {
        val now = helper.nowElapsedRealtimeMs()
        val newStatus = AapNavigationHelper.TimedMessage(status, now)
        val previous = snapshot.clusterStatus?.payload?.status
        val changed = previous != null && previous != status.status
        if (changed) {
            clearAccumulatedDataPreservingStatus(newStatus)
            helper.cancelNotification()
        } else {
            snapshot.clusterStatus = newStatus
        }
    }

    private fun scheduleDebouncedBroadcast(navEventType: Int) {
        val bydFrame = if (settings.bydNavigationEnabled) com.andrerinas.openheadunit.hud.BydNavigationMapper.from(snapshot) else null
        AppLog.i("BYD nav input event=$navEventType enabled=${settings.bydNavigationEnabled} " +
            "status=${snapshot.clusterStatus?.payload?.status} steps=${snapshot.navigationState?.payload?.stepsCount} " +
            "legacy=${snapshot.nextTurnDetail?.payload?.nextTurn} position=${snapshot.currentPosition != null} " +
            "distance=${bydFrame?.distanceMeters} icon=${bydFrame?.clusterIcon}")
        com.andrerinas.openheadunit.hud.BydNavigationOutputs.update(bydFrame)
        pendingNavEventType = navEventType
        if (isBroadcastScheduled) return
        isBroadcastScheduled = true
        debounceHandler.postDelayed(debouncedBroadcastEmitter, BROADCAST_DEBOUNCE_MS)
    }

    companion object {
        private const val BROADCAST_DEBOUNCE_MS = 1000L
        private const val NAV_EVENT_TYPE_TURN = 0
        private const val NAV_EVENT_TYPE_START = 1
        private const val NAV_EVENT_TYPE_STOP = 2
        private const val NAV_EVENT_TYPE_STATUS = 3
        private const val NAV_EVENT_TYPE_STATE = 4
        private const val NAV_EVENT_TYPE_CURRENT_POSITION = 5

        fun createNotificationChannel(context: Context) {
            AapNavigationHelper.createNotificationChannel(context)
        }
    }
}