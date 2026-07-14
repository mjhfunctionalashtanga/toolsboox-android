package com.toolsboox.ui.plugin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.toolsboox.R
import com.toolsboox.databinding.ToolbarBinding
import com.toolsboox.ui.main.MainActivity
import timber.log.Timber

/**
 * The fragment base class.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
abstract class ScreenFragment : Fragment() {
    companion object {

        /**
         * Result code of ask permissions.
         */
        const val REQUEST_PERMISSIONS = 12345

        /**
         * User already asked for permissions.
         */
        private var askedForPermissions: Boolean = false
    }

    /**
     * The toolbar of the parent activity.
     */
    lateinit var toolbar: ToolbarBinding

    /**
     * The view resource.
     */
    protected open val view: Int? = null

    /**
     * OnCreateView hook.
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val createdView = view?.let { inflater.inflate(it, container, false) }
        toolbar = (requireActivity() as MainActivity).getToolbar()
        return createdView
    }

    /**
     * Show the 'something happened' error...
     *
     * @param silent log only the error
     * @param t the optional throwable
     * @param parentView the optional parent view of the snackbar
     */
    fun somethingHappened(silent: Boolean = false, t: Throwable? = null, parentView: View? = null) {
        runOnActivity {
            showError(t, R.string.something_happened_error, parentView, silent)
        }
    }

    /**
     * Show the 'something happened' error...
     *
     * @param t the optional throwable
     * @param parentView the optional parent view of the snackbar
     */
    fun somethingHappened(t: Throwable? = null, parentView: View? = null) {
        runOnActivity {
            showError(t, R.string.something_happened_error, parentView)
        }
    }

    /**
     * Displays an error in the view.
     *
     * @param t the optional throwable
     * @param errorResId the resource id of the error
     * @param parentView the optional parent view of the snackbar
     * @param silent log only the error
     */
    open fun showError(t: Throwable?, @StringRes errorResId: Int, parentView: View? = null, silent: Boolean = false) {
        t?.let { Timber.e(it, getString(errorResId)) }
        if (silent) return

        val snackbar = Snackbar.make(
            parentView?.let { parentView } ?: toolbar.root, errorResId, Snackbar.LENGTH_INDEFINITE
        )
        snackbar.setAction(R.string.something_happened_action) {}
        snackbar.show()
    }

    /**
     * Displays an error in the view.
     *
     * @param message the message
     * @param parentView the optional parent view of the snackbar
     */
    open fun showMessage(message: String, parentView: View? = null) {
        Snackbar.make(parentView?.let { parentView } ?: toolbar.root, message, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Displays an error in the view.
     *
     * @param messageResId the resource id of the error
     * @param parentView the optional parent view of the snackbar
     */
    open fun showMessage(@StringRes messageResId: Int, parentView: View? = null) {
        showMessage(getString(messageResId), parentView)
    }

    /**
     * Call the function on the foreground activity only.
     *
     * @param call the function
     */
    fun runOnActivity(call: () -> Unit) {
        activity?.let {
            if (isAdded) call()
        }
    }

    /**
     * Displays the loading indicator of the view.
     */
    abstract fun showLoading()

    /**
     * Hides the loading indicator of the view.
     */
    abstract fun hideLoading()

    /** Drag [handle] to move [pill] freely (via translation), persisted under [key]. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    protected fun makeDraggable(handle: View, pill: View, key: String) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        pill.translationX = prefs.getFloat("${key}_tx", 0f)
        pill.translationY = prefs.getFloat("${key}_ty", 0f)
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startTx = pill.translationX; startTy = pill.translationY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    pill.translationX = startTx + (e.rawX - downX)
                    pill.translationY = startTy + (e.rawY - downY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putFloat("${key}_tx", pill.translationX)
                        .putFloat("${key}_ty", pill.translationY).apply()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * A directory popover (top-left, iPad-style) grouping labelled rows under headers —
     * used for the hamburger menus on Bookshelf/Feed to list the actual books/feeds plus
     * the surfaces to jump to. Each row is (label, action).
     */
    protected fun showDirectory(groups: List<Pair<String, List<Pair<String, () -> Unit>>>>) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        for ((header, items) in groups) {
            if (header.isNotEmpty()) {
                val tv = TextView(requireContext())
                tv.text = header.uppercase()
                tv.setTextColor(0xFF8A8A8A.toInt()); tv.textSize = 11f; tv.letterSpacing = 0.08f
                tv.setPadding(dp(14), dp(12), dp(14), dp(2))
                list.addView(tv)
            }
            for ((label, action) in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<ImageView>(R.id.go_icon).visibility = View.GONE
                r.findViewById<TextView>(R.id.go_label).text = label
                r.setOnClickListener { dialog.dismiss(); action() }
                list.addView(r)
            }
        }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.gravity = Gravity.START or Gravity.TOP
            lp.x = dp(8); lp.y = dp(54); lp.width = dp(250)
            lp.height = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            w.attributes = lp
        }
    }

    /**
     * Persistent "Go to" surfaces menu, available on every screen (day, Bookshelf, Feed,
     * Ask). Lets you jump between the Ledger surfaces from anywhere.
     */
    protected fun showSurfacesMenu() {
        val labels = arrayOf("Day", "Bookshelf", "Feed Ledger", "Ask my Ledger")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.go_to_title)
            .setItems(labels) { _, which ->
                val nav = androidx.navigation.fragment.NavHostFragment.findNavController(this)
                when (which) {
                    0 -> nav.navigate(R.id.action_to_calendar_day)
                    1 -> nav.navigate(R.id.action_to_reader)
                    2 -> nav.navigate(R.id.action_to_feeds)
                    3 -> nav.navigate(R.id.action_to_ledger_chat)
                }
            }
            .show()
    }

    /**
     * Result of request permission.
     */
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                var allGranted = true
                var someGranted = false

                grantResults.forEach {
                    allGranted = allGranted and (it == PackageManager.PERMISSION_GRANTED)
                    someGranted = someGranted or (it == PackageManager.PERMISSION_GRANTED)
                }

                val message = if (allGranted) {
                    R.string.main_all_granted_message
                } else if (someGranted) {
                    R.string.main_some_granted_message
                } else {
                    R.string.main_all_denied_message
                }

                Toast.makeText(this.context, message, Toast.LENGTH_LONG).show()
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Check if permission granted or start the request process.
     */
    fun askAppPermissions() {
        if (askedForPermissions) return
        askedForPermissions = true

        val permissionsNeeded = mutableListOf<String>()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.INTERNET)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.READ_CALENDAR)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.READ_EXTERNAL_STORAGE)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permissionsList.isEmpty()) return

        if (permissionsNeeded.isEmpty()) {
            requestPermissions(permissionsList.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            val message = getString(R.string.main_ask_permissions_message, permissionsNeeded.joinToString { it })
            val builder: AlertDialog.Builder = AlertDialog.Builder(this.requireContext())
            builder.setTitle(R.string.main_ask_permissions_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(permissionsList.toTypedArray(), REQUEST_PERMISSIONS)
                }
            builder.create().show()
        }
    }

    /**
     * Check permission.
     *
     * @param permissionName the name of the permission
     * @return true, if granted
     */
    fun checkPermission(permissionName: String): Boolean {
        if (Manifest.permission.READ_EXTERNAL_STORAGE == permissionName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return true
            }
        }
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE == permissionName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return true
            }
        }

        return ContextCompat.checkSelfPermission(requireContext(), permissionName) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check and add permission to the lists.
     *
     * @param permissionsList the list of permissions to acquire
     * @param permissionNeeded the list of permissions to ask about
     * @param permissionName the name of the permission
     */
    private fun checkAndAddPermission(
        permissionsList: MutableList<String>,
        permissionNeeded: MutableList<String>,
        permissionName: String
    ) {
        if (checkPermission(permissionName)) return

        permissionsList.add(permissionName)
        if (ActivityCompat.shouldShowRequestPermissionRationale(this.requireActivity(), permissionName)) {
            permissionNeeded.add(permissionName)
        }
    }
}
