package org.bayton.gmc.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, VERIFYING, ERROR }
internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateManifest? = null,
    val progress: Float? = null,
    val message: String? = null,
    val apk: File? = null,
)

// The one-argument constructor remains available to Android's default ViewModel factory.
// Optional collaborators let lifecycle tests use local responses without changing CDN policy.
internal class UpdateViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: UpdateRepository = UpdateRepository(),
    val installed: ApkIdentity = installedIdentity(application),
    private val validateFile: (File, UpdateManifest) -> Unit = { file, release -> validateApk(application, file, release) },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var awaitingInstallPermission = false

    fun check() = runOperation {
        awaitingInstallPermission = false
        mutableState.value.apk?.delete()
        mutableState.value = UpdateState(UpdatePhase.CHECKING)
        val release = repository.check()
        mutableState.value = UpdateState(
            if (release.isNewerThan(installed.versionCode)) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
            release,
        )
    }

    fun download() {
        val release = state.value.release ?: return
        runOperation {
            mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, release)
            val directory = File(getApplication<Application>().cacheDir, "updates")
            withContext(Dispatchers.IO) {
                // Only updater-owned cache files are eligible; never touch the account cache.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                    ?.forEach { it.delete() }
            }
            val downloadJob = currentCoroutineContext().job
            val apk = repository.download(release, directory, { validateFile(it, release) }) { received, total ->
                // Serialise progress with Cancel on the main thread. Late network callbacks cannot
                // overwrite cancellation, a completed download or the state of a newer operation.
                viewModelScope.launch {
                    if (downloadJob.isActive && state.value.phase == UpdatePhase.DOWNLOADING) {
                        mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, release,
                            total?.takeIf { it > 0 }?.let { (received.toDouble() / it).toFloat().coerceIn(0f, 1f) })
                    }
                }
            }
            mutableState.value = UpdateState(UpdatePhase.READY, release, apk = apk)
        }
    }

    fun prepareInstall(launch: (File) -> Unit) {
        val ready = state.value
        val apk = ready.apk ?: return
        val release = ready.release ?: return
        runOperation {
            mutableState.value = ready.copy(phase = UpdatePhase.VERIFYING, message = null)
            withContext(Dispatchers.IO) {
                require(sha256(apk).equals(release.sha256, true)) { "The cached APK changed. Download it again." }
                validateFile(apk, release)
            }
            mutableState.value = ready.copy(message = "Android will ask you to confirm the update. If you cancel, you can try again here.")
            launch(apk)
        }
    }

    fun installPermissionNeeded() {
        awaitingInstallPermission = true
        mutableState.value = state.value.copy(message = "Allow updates from Gwent Music, then return here to continue installation.")
    }

    /** Resume only an explicitly requested install, once, and revalidate after leaving the app. */
    fun installPermissionReturned(granted: Boolean, launch: (File) -> Unit) {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        if (state.value.phase != UpdatePhase.READY) return
        if (granted) prepareInstall(launch)
        else mutableState.value = state.value.copy(message = "Installation permission was not granted. Tap Install update to try again.")
    }

    fun cancel() {
        awaitingInstallPermission = false
        operation?.cancel()
        mutableState.value.apk?.delete()
        mutableState.value = UpdateState()
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value.apk?.delete()
                mutableState.value = UpdateState(UpdatePhase.ERROR, message =
                    if (e is IOException) "Could not complete the update request. Check your connection and try again."
                    else e.message ?: "Could not prepare this update. Please try again.")
            }
        }
    }
}
