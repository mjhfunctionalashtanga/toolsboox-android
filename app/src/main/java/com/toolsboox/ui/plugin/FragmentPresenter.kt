package com.toolsboox.ui.plugin

import android.Manifest
import android.os.Build
import android.os.Environment
import android.view.View
import androidx.fragment.app.Fragment
import com.toolsboox.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.File

/**
 * Base class of fragment presenters.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
abstract class FragmentPresenter {

    /**
     * Check and ask common permissions of the fragment.
     *
     * @param fragment the screen fragment
     * @return the common permissions state
     */
    protected fun checkPermissions(fragment: ScreenFragment, view: View): Boolean {
        if (!fragment.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            fragment.showError(null, R.string.main_read_external_storage_permission_missing, view)
            return false
        }

        if (!fragment.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            fragment.showError(null, R.string.main_write_external_storage_permission_missing, view)
            return false
        }

        return true
    }

    /**
     * Coroutines based call helper with default onError.
     *
     * @param fragment the fragment
     * @param call the backend call
     * @param onSuccess the success path function
     */
    protected fun <T> coroutinesCallHelper(
        fragment: ScreenFragment,
        call: () -> Deferred<Response<T>>,
        onSuccess: (response: Response<T>) -> Unit,
        silent: Boolean = false
    ) = coroutinesCallHelper(fragment, call, onSuccess) { t -> fragment.somethingHappened(silent, t) }

    /**
     * Coroutines based call helper.
     *
     * @param fragment the fragment
     * @param call the backend call
     * @param onSuccess the success path function
     * @param onError the custom onError function
     */
    private fun <T> coroutinesCallHelper(
        fragment: ScreenFragment,
        call: () -> Deferred<Response<T>>,
        onSuccess: (response: Response<T>) -> Unit,
        onError: (t: Throwable) -> Unit
    ) = GlobalScope.launch(Dispatchers.Main) {
        try {
            fragment.runOnActivity {
                fragment.showLoading()
            }

            // The GlobalScope job outlives the fragment; every onSuccess passed in touches
            // fragment views/context. Route it through runOnActivity so a response landing
            // after navigation is dropped instead of crashing on a dead fragment.
            val response = call().await()
            fragment.runOnActivity { onSuccess(response) }
        } catch (t: Throwable) {
            fragment.runOnActivity { onError(t) }
        } finally {
            fragment.runOnActivity {
                fragment.hideLoading()
            }
        }
    }

    /**
     * Returns with the root path of the writable filesystem.
     *
     * @param fragment the fragment
     * @param fallback the fallback path component
     */
    companion object {
        /** The process's application context and the last Documents root it resolved — the
         *  fallbacks a detached fragment can no longer provide (see rootPath). */
        @Volatile private var cachedAppContext: android.content.Context? = null
        @Volatile private var cachedDocumentsRoot: File? = null
    }

    protected fun rootPath(fragment: Fragment, fallback: String): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (fallback) {
                Environment.DIRECTORY_DOCUMENTS -> {
                    // NEVER `requireContext()!!` here (audit 09-10): every caller runs on
                    // Dispatchers.IO inside GlobalScope with only IOException caught, and the
                    // day page's per-stroke save is one of them — lift the pen, back out, and the
                    // fragment is detached when the job runs: IllegalStateException, uncaught in
                    // GlobalScope, process gone with the stroke. The app context outlives the
                    // fragment, and the last resolved root stands in when even that is missing.
                    val ctx = fragment.context?.applicationContext ?: cachedAppContext
                    cachedAppContext = ctx ?: cachedAppContext
                    val root = ctx?.let { it.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: it.filesDir }
                        ?: cachedDocumentsRoot
                        ?: File(Environment.getExternalStorageDirectory(), "toolsBoox")
                    cachedDocumentsRoot = root
                    root
                }

                else -> {
                    val rootPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File(rootPath, "toolsBoox")
                }
            }
        } else {
            when (fallback) {
                Environment.DIRECTORY_DOCUMENTS -> {
                    val rootPath = Environment.getExternalStorageDirectory()
                    File(rootPath, "toolsBoox")
                }

                else -> {
                    Environment.getExternalStorageDirectory()
                }
            }
        }
    }
}
