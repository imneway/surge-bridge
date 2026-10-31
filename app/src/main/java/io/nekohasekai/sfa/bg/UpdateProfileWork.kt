package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        fun checkDueSoon() {
            WorkManager.getInstance(Application.application).enqueueUniqueWork(
                "UpdateProfileDueCheck",
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequest.Builder(UpdateTask::class.java)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            var minDelay =
                remoteProfiles.minByOrNull { it.typed.autoUpdateInterval }!!.typed.autoUpdateInterval.toLong()
            val nowMillis = System.currentTimeMillis()
            val minInitDelay =
                remoteProfiles.minOf { it.typed.lastUpdated.time + it.typed.autoUpdateInterval.toLong() * 60_000 - nowMillis }
            if (minDelay < 15) minDelay = 15
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE retains the old enqueue time. Applying a newly computed remaining
                // delay to that time can run early, skip a not-yet-due profile, then wait a
                // whole extra period. Re-enqueue so this delay is relative to now.
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.MILLISECONDS)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = if (refreshDueProfiles()) Result.success() else Result.retry()
    }
}

suspend fun refreshDueProfiles(): Boolean {
    var selectedProfileUpdated = false
    val remoteProfiles =
        ProfileManager.list()
            .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
    if (remoteProfiles.isEmpty()) return true
    var success = true
    val selectedProfile = Settings.selectedProfile
    for (profile in remoteProfiles) {
        val lastSeconds =
            (System.currentTimeMillis() - profile.typed.lastUpdated.time) / 1000L
        if (lastSeconds < profile.typed.autoUpdateInterval.toLong() * 60) {
            continue
        }
        try {
            if (io.nekohasekai.sfa.surge.ProfileRefresh.refresh(profile, onlyIfDue = true)) {
                if (profile.id == selectedProfile) {
                    selectedProfileUpdated = true
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateProfileWork", "profile update failed")
            success = false
        }
    }
    if (selectedProfileUpdated && Settings.selectedProfile == selectedProfile) {
        runCatching {
            Libbox.newStandaloneCommandClient().serviceReload()
        }
    }
    return success
}
