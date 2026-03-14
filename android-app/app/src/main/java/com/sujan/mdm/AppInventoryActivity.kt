package com.sujan.mdm

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppInventoryActivity : AppCompatActivity() {

    private lateinit var rvApps     : RecyclerView
    private lateinit var etSearch   : EditText
    private lateinit var btnAll     : Button
    private lateinit var btnUser    : Button
    private lateinit var btnSystem  : Button
    private lateinit var tvTotal    : TextView
    private lateinit var tvSystem   : TextView
    private lateinit var tvUser     : TextView
    private lateinit var tvLoading  : TextView
    private lateinit var progressBar: ProgressBar

    private var allApps       = listOf<AppDisplayItem>()
    private var currentFilter = "all"
    private lateinit var adapter: AppListAdapter

    data class AppDisplayItem(
        val appName      : String,
        val packageName  : String,
        val versionName  : String,
        val isSystemApp  : Boolean,
        val installSource: String,
        val icon         : Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_inventory)
        supportActionBar?.hide()

        rvApps      = findViewById(R.id.rvApps)
        etSearch    = findViewById(R.id.etSearch)
        btnAll      = findViewById(R.id.btnAll)
        btnUser     = findViewById(R.id.btnUser)
        btnSystem   = findViewById(R.id.btnSystem)
        tvTotal     = findViewById(R.id.tvTotalCount)
        tvSystem    = findViewById(R.id.tvSystemCount)
        tvUser      = findViewById(R.id.tvUserCount)
        tvLoading   = findViewById(R.id.tvLoading)
        progressBar = findViewById(R.id.progressBar)

        adapter = AppListAdapter(emptyList())
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter       = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnAll.setOnClickListener    { setFilter("all")    }
        btnUser.setOnClickListener   { setFilter("user")   }
        btnSystem.setOnClickListener { setFilter("system") }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { applyFilterAndSearch() }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
    }

    private fun loadApps() {
        tvLoading.visibility   = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        rvApps.visibility      = View.GONE

        lifecycleScope.launch {
            val pm       = packageManager
            val packages = withContext(Dispatchers.IO) { pm.getInstalledPackages(0) }

            val items = withContext(Dispatchers.IO) {
                packages.mapNotNull { pkg ->
                    val appInfo  = pkg.applicationInfo ?: return@mapNotNull null
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val source   = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pm.getInstallSourceInfo(pkg.packageName).installingPackageName ?: "Unknown"
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getInstallerPackageName(pkg.packageName) ?: "Unknown"
                        }
                    } catch (e: Exception) { "Unknown" }

                    AppDisplayItem(
                        appName       = appInfo.loadLabel(pm).toString(),
                        packageName   = pkg.packageName,
                        versionName   = pkg.versionName ?: "N/A",
                        isSystemApp   = isSystem,
                        installSource = source,
                        icon          = try { appInfo.loadIcon(pm) } catch (e: Exception) { null }
                    )
                }.sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
            }

            allApps = items
            val total  = items.size
            val system = items.count {  it.isSystemApp }
            val user   = items.count { !it.isSystemApp }

            tvTotal.text  = total.toString()
            tvSystem.text = system.toString()
            tvUser.text   = user.toString()

            tvLoading.visibility   = View.GONE
            progressBar.visibility = View.GONE
            rvApps.visibility      = View.VISIBLE

            applyFilterAndSearch()
        }
    }

    private fun setFilter(filter: String) {
        currentFilter = filter

        // Active = dark blue, Inactive = light grey — matches main screen style
        val activeColor   = 0xFF1565C0.toInt()   // dark blue
        val inactiveColor = 0xFFE8EAF6.toInt()   // light blue-grey
        val activeText    = 0xFFFFFFFF.toInt()   // white
        val inactiveText  = 0xFF757575.toInt()   // grey

        listOf(btnAll to "all", btnUser to "user", btnSystem to "system").forEach { (btn, f) ->
            if (f == filter) {
                btn.setBackgroundColor(activeColor)
                btn.setTextColor(activeText)
            } else {
                btn.setBackgroundColor(inactiveColor)
                btn.setTextColor(inactiveText)
            }
        }
        applyFilterAndSearch()
    }

    private fun applyFilterAndSearch() {
        val query = etSearch.text.toString().lowercase().trim()
        var list  = allApps

        when (currentFilter) {
            "user"   -> list = list.filter { !it.isSystemApp }
            "system" -> list = list.filter {  it.isSystemApp }
        }

        if (query.isNotEmpty()) {
            list = list.filter {
                it.appName.lowercase().contains(query) ||
                        it.packageName.lowercase().contains(query)
            }
        }

        adapter.updateList(list)
        findViewById<TextView>(R.id.tvResultCount).text =
            "${list.size} app${if (list.size != 1) "s" else ""}"
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────
    inner class AppListAdapter(
        private var items: List<AppDisplayItem>
    ) : RecyclerView.Adapter<AppListAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon   : ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName   : TextView  = view.findViewById(R.id.tvAppName)
            val tvPkg    : TextView  = view.findViewById(R.id.tvAppPackage)
            val tvVersion: TextView  = view.findViewById(R.id.tvAppVersion)
            val tvSource : TextView  = view.findViewById(R.id.tvAppSource)
            val tvBadge  : TextView  = view.findViewById(R.id.tvAppBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            holder.tvName.text    = app.appName
            holder.tvPkg.text     = app.packageName
            holder.tvVersion.text = "v${app.versionName}"
            holder.tvSource.text  = app.installSource

            if (app.icon != null) {
                holder.ivIcon.setImageDrawable(app.icon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Badge colors matching main screen palette
            if (app.isSystemApp) {
                holder.tvBadge.text = "SYSTEM"
                holder.tvBadge.setBackgroundColor(0xFFE8F5E9.toInt())  // light green bg
                holder.tvBadge.setTextColor(0xFF2E7D32.toInt())         // dark green text
            } else {
                holder.tvBadge.text = "USER"
                holder.tvBadge.setBackgroundColor(0xFFF3E5F5.toInt())  // light purple bg
                holder.tvBadge.setTextColor(0xFF6A1B9A.toInt())         // dark purple text
            }
        }

        override fun getItemCount() = items.size

        fun updateList(newList: List<AppDisplayItem>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}