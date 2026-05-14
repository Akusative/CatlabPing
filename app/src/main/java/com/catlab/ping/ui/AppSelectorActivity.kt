/*
 * CatlabPing - 监控应用选择页
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 列出手机上所有已安装的用户App（排除系统App），
 * 勾选需要监控的App，保存后写入SharedPreferences。
 * AppMonitorService 上报前会检查当前App是否在勾选列表中。
 */

package com.catlab.ping.ui

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.catlab.ping.R
import com.google.android.material.button.MaterialButton

data class AppItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean = false
)

class AppSelectorActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppListAdapter
    private lateinit var tvSelectedCount: TextView

    private var allApps: List<AppItem> = emptyList()
    private var filteredApps: List<AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selector)

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)

        val etSearch = findViewById<EditText>(R.id.et_search)
        val rvApps = findViewById<RecyclerView>(R.id.rv_apps)
        val btnSave = findViewById<MaterialButton>(R.id.btn_save)
        val btnSelectAll = findViewById<MaterialButton>(R.id.btn_select_all)
        val btnDeselectAll = findViewById<MaterialButton>(R.id.btn_deselect_all)
        tvSelectedCount = findViewById(R.id.tv_selected_count)

        // 加载已保存的勾选列表
        val savedApps = loadSelectedPackages()

        // 加载已安装的用户App
        allApps = loadInstalledApps(savedApps)
        filteredApps = allApps
        updateSelectedCount()

        adapter = AppListAdapter(filteredApps) { updateSelectedCount() }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        // 搜索过滤
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                filteredApps = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.appName.lowercase().contains(query) ||
                        it.packageName.lowercase().contains(query)
                    }
                }
                adapter.updateList(filteredApps)
            }
        })

        // 全选
        btnSelectAll.setOnClickListener {
            filteredApps.forEach { it.isSelected = true }
            adapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        // 取消全选
        btnDeselectAll.setOnClickListener {
            filteredApps.forEach { it.isSelected = false }
            adapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        // 保存
        btnSave.setOnClickListener {
            saveSelectedPackages()
            Toast.makeText(this, "✅ 监控应用列表已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadInstalledApps(savedPackages: Set<String>): List<AppItem> {
        val pm = packageManager
        val apps = mutableListOf<AppItem>()

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in installedApps) {
            // 排除系统App（但保留用户可能关心的系统级App如设置、相机等）
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // 排除自己
            if (appInfo.packageName == packageName) continue

            // 只显示有启动入口的App（排除纯后台服务）
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent == null && isSystemApp && !isUpdatedSystem) continue

            try {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                val isSelected = savedPackages.contains(appInfo.packageName)
                apps.add(AppItem(appName, appInfo.packageName, icon, isSelected))
            } catch (e: Exception) {
                // 跳过无法获取信息的App
            }
        }

        // 按名称排序，已勾选的排在前面
        return apps.sortedWith(compareByDescending<AppItem> { it.isSelected }.thenBy { it.appName })
    }

    private fun loadSelectedPackages(): Set<String> {
        val saved = prefs.getString("monitor_apps_packages", "") ?: ""
        if (saved.isBlank()) {
            // 兼容旧版：从 monitor_apps 文本格式迁移
            val oldFormat = prefs.getString("monitor_apps", "") ?: ""
            if (oldFormat.isNotBlank()) {
                val packages = mutableSetOf<String>()
                for (line in oldFormat.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val pkg = if ("|" in trimmed) trimmed.split("|", limit = 2)[1].trim() else trimmed
                    packages.add(pkg)
                }
                return packages
            }
            return emptySet()
        }
        return saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun saveSelectedPackages() {
        val selectedPackages = allApps.filter { it.isSelected }.map { it.packageName }
        val selectedDisplay = allApps.filter { it.isSelected }.joinToString("\n") { "${it.appName}|${it.packageName}" }

        prefs.edit().apply {
            // 新格式：纯包名列表，用于 AppMonitorService 快速查询
            putString("monitor_apps_packages", selectedPackages.joinToString("\n"))
            // 兼容旧格式：显示名称|包名，用于设置页显示
            putString("monitor_apps", selectedDisplay)
            apply()
        }
    }

    private fun updateSelectedCount() {
        val count = allApps.count { it.isSelected }
        tvSelectedCount.text = "已选择 $count 个应用"
    }

    // ========== RecyclerView Adapter ==========

    class AppListAdapter(
        private var apps: List<AppItem>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app_selector, parent, false)
        ) {
            val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
            val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
            val tvPackage: TextView = itemView.findViewById(R.id.tv_package_name)
            val cbSelected: CheckBox = itemView.findViewById(R.id.cb_selected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.ivIcon.setImageDrawable(app.icon)
            holder.tvName.text = app.appName
            holder.tvPackage.text = app.packageName

            // 防止 RecyclerView 复用导致的 CheckBox 状态错乱
            holder.cbSelected.setOnCheckedChangeListener(null)
            holder.cbSelected.isChecked = app.isSelected
            holder.cbSelected.setOnCheckedChangeListener { _, isChecked ->
                app.isSelected = isChecked
                onSelectionChanged()
            }

            // 点击整行也能切换勾选
            holder.itemView.setOnClickListener {
                holder.cbSelected.isChecked = !holder.cbSelected.isChecked
            }
        }

        override fun getItemCount() = apps.size

        fun updateList(newApps: List<AppItem>) {
            apps = newApps
            notifyDataSetChanged()
        }
    }
}
