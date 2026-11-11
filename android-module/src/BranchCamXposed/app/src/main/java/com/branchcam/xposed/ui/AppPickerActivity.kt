package com.branchcam.xposed.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.branchcam.xposed.Prefs
import com.branchcam.xposed.SelectedVideoProvider
import com.branchcam.xposed.R
import com.branchcam.xposed.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private val prefs by lazy { Prefs.get(this) }
    private val adapter = AppAdapter()
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Load previously selected packages
        val json = prefs.getString(Prefs.KEY_APPS, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            repeat(arr.length()) { selected.add(arr.getString(it)) }
        } catch (_: Exception) {}

        // "Global mode" toggle — if checked all apps are targeted
        b.switchGlobal.isChecked = selected.isEmpty()
        updateGlobalUI(selected.isEmpty())
        b.switchGlobal.setOnCheckedChangeListener { _, on ->
            if (on) {
                selected.clear()
                adapter.notifyDataSetChanged()
            }
            updateGlobalUI(on)
            saveSelection()
        }

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(text: String?): Boolean {
                adapter.filter(text ?: "")
                return true
            }
        })

        b.btnClearAll.setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            saveSelection()
        }

        loadApps()
    }

    private fun updateGlobalUI(global: Boolean) {
        b.recycler.alpha       = if (global) 0.4f else 1f
        b.recycler.isEnabled   = !global
        b.globalDesc.text      = if (global)
            "BranchCam will intercept ALL apps (global)"
        else
            "Only selected apps will have fake camera"
    }

    private fun loadApps() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }  // user apps only
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

            withContext(Dispatchers.Main) {
                b.progress.visibility = View.GONE
                adapter.setData(apps)
            }
        }
    }

    private fun saveSelection() {
        val json = JSONArray(selected.toList()).toString()
        prefs.edit().putString(Prefs.KEY_APPS, if (selected.isEmpty()) "[]" else json).commit()
        SelectedVideoProvider.syncGrants(this)
    }

    // ── RecyclerView Adapter ───────────────────────────────────────────────

    inner class AppAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<AppAdapter.VH>() {

        private var all: List<ApplicationInfo> = emptyList()
        private var filtered: List<ApplicationInfo> = emptyList()

        fun setData(list: List<ApplicationInfo>) {
            all = list
            filtered = list
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filtered = if (query.isEmpty()) all
            else all.filter {
                val label = packageManager.getApplicationLabel(it).toString()
                label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = filtered[position]
            val pm   = packageManager
            holder.icon.setImageDrawable(pm.getApplicationIcon(info))
            holder.name.text = pm.getApplicationLabel(info).toString()
            holder.pkg.text  = info.packageName
            holder.check.isChecked = selected.contains(info.packageName)

            val toggle = {
                if (selected.contains(info.packageName)) {
                    selected.remove(info.packageName)
                } else {
                    selected.add(info.packageName)
                    // If user picks an app, turn off global mode
                    if (b.switchGlobal.isChecked) {
                        b.switchGlobal.isChecked = false
                        updateGlobalUI(false)
                    }
                }
                holder.check.isChecked = selected.contains(info.packageName)
                saveSelection()
            }
            holder.itemView.setOnClickListener { toggle() }
            holder.check.setOnClickListener { toggle() }
        }

        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val icon:  ImageView = v.findViewById(R.id.appIcon)
            val name:  TextView  = v.findViewById(R.id.appName)
            val pkg:   TextView  = v.findViewById(R.id.appPkg)
            val check: CheckBox  = v.findViewById(R.id.appCheck)
        }
    }
}
