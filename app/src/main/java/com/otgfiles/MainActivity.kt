package com.otgfiles

import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.otgfiles.fs.NtfsBackend
import com.otgfiles.fs.NtfsEntry
import com.otgfiles.fs.UsbMassStorageBlockDevice
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusSubtitle: TextView
    private lateinit var driveTitle: TextView
    private lateinit var storageInfo: TextView
    private lateinit var storageProgress: ProgressBar
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnNewFolder: MaterialButton
    private lateinit var btnPaste: MaterialButton
    private lateinit var btnCancelClip: MaterialButton
    private lateinit var pathBreadcrumb: TextView
    private lateinit var itemCountText: TextView
    private lateinit var recyclerView: RecyclerView

    private var blockDevice: UsbMassStorageBlockDevice? = null
    private var ntfs: NtfsBackend? = null
    private var currentPath = "/"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Clipboard state for Copy / Move
    private data class ClipboardItem(
        val fullPath: String,
        val name: String,
        val isDir: Boolean,
        val isMove: Boolean
    )
    private var clipboard: ClipboardItem? = null
    private var pendingExportEntry: NtfsEntry? = null

    private val ACTION_USB_PERMISSION = "com.otgfiles.USB_PERMISSION"

    // SAF Picker for Importing file from Phone -> USB
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handleImportUri(uri)
    }

    // SAF Creator for Exporting file from USB -> Phone
    private val exportCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val entry = pendingExportEntry
        if (uri != null && entry != null) {
            handleExportUri(uri, entry)
        }
        pendingExportEntry = null
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    openUsbDevice(device)
                } else {
                    Toast.makeText(this@MainActivity, "USB Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        statusSubtitle = findViewById(R.id.statusSubtitle)
        driveTitle = findViewById(R.id.driveTitle)
        storageInfo = findViewById(R.id.storageInfo)
        storageProgress = findViewById(R.id.storageProgress)
        btnConnect = findViewById(R.id.btnConnect)
        btnImport = findViewById(R.id.btnImport)
        btnNewFolder = findViewById(R.id.btnNewFolder)
        btnPaste = findViewById(R.id.btnPaste)
        btnCancelClip = findViewById(R.id.btnCancelClip)
        pathBreadcrumb = findViewById(R.id.pathBreadcrumb)
        itemCountText = findViewById(R.id.itemCountText)
        recyclerView = findViewById(R.id.recyclerView)

        val lm = LinearLayoutManager(this)
        recyclerView.layoutManager = lm
        recyclerView.addItemDecoration(DividerItemDecoration(this, lm.orientation))

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { scanAndConnect() }
        btnImport.setOnClickListener { importPicker.launch(arrayOf("*/*")) }
        btnNewFolder.setOnClickListener { promptNewFolder() }
        btnPaste.setOnClickListener { executePaste() }
        btnCancelClip.setOnClickListener {
            clipboard = null
            updateClipboardUI()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath != "/" && ntfs != null) {
                    val parent = currentPath.substringBeforeLast('/')
                    currentPath = if (parent.isEmpty()) "/" else parent
                    loadDir(currentPath)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun scanAndConnect() {
        val device = usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
        if (device == null) {
            Toast.makeText(this, "No USB OTG drive detected", Toast.LENGTH_SHORT).show()
            return
        }
        if (usbManager.hasPermission(device)) {
            openUsbDevice(device)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        statusSubtitle.text = "NTFS USB Storage • Mounting..."
        scope.launch {
            val errorMsg = withContext(Dispatchers.IO) {
                var usbIf: UsbInterface? = null
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                        usbIf = iface
                        for (e in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(e)
                            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                            }
                        }
                        break
                    }
                }
                if (usbIf == null || epIn == null || epOut == null) return@withContext "Invalid USB endpoints"
                val conn = usbManager.openDevice(device) ?: return@withContext "Failed to open USB connection"
                val blk = UsbMassStorageBlockDevice(conn, usbIf, epIn, epOut)
                if (!blk.init()) {
                    blk.close()
                    return@withContext "SCSI Init failed"
                }
                val backend = NtfsBackend(blk)
                val rc = backend.mount(false)
                if (rc != 0) {
                    blk.close()
                    return@withContext "NTFS Mount failed (code $rc)"
                }
                blockDevice = blk
                ntfs = backend
                null
            }

            if (errorMsg != null) {
                statusSubtitle.text = "NTFS USB Storage • Error"
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            } else {
                driveTitle.text = device.productName ?: "USB Drive (NTFS)"
                statusSubtitle.text = "NTFS USB Storage • Connected (Read/Write)"
                btnConnect.text = "Refresh"
                btnImport.isEnabled = true
                btnNewFolder.isEnabled = true
                refreshStorageStats()
                currentPath = "/"
                loadDir(currentPath)
            }
        }
    }

    private fun refreshStorageStats() {
        val backend = ntfs ?: return
        scope.launch {
            val (total, free) = withContext(Dispatchers.IO) {
                Pair(backend.totalSpace(), backend.freeSpace())
            }
            if (total > 0) {
                val used = total - free
                val usedStr = Formatter.formatShortFileSize(this@MainActivity, used)
                val totalStr = Formatter.formatShortFileSize(this@MainActivity, total)
                val freeStr = Formatter.formatShortFileSize(this@MainActivity, free)
                storageInfo.text = "$usedStr used / $totalStr  ($freeStr free)"
                storageProgress.visibility = View.VISIBLE
                storageProgress.progress = ((used * 100) / total).toInt()
            }
        }
    }

    private fun loadDir(path: String) {
        pathBreadcrumb.text = if (path == "/") "USB Storage  ›  Root"
        else "USB Storage  ›  " + path.trimStart('/').replace("/", "  ›  ")

        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                ntfs?.list(path) ?: emptyList()
            }
            itemCountText.text = "${entries.size} items"
            recyclerView.adapter = FileAdapter(
                items = entries,
                onClick = { entry ->
                    if (entry.isDir) {
                        currentPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
                        loadDir(currentPath)
                    } else {
                        showItemOptionsSheet(entry)
                    }
                },
                onMoreClick = { entry ->
                    showItemOptionsSheet(entry)
                }
            )
        }
    }

    private fun showItemOptionsSheet(entry: NtfsEntry) {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 48)
        }

        val title = TextView(this).apply {
            text = entry.name
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        fun addOption(label: String, isDanger: Boolean = false, action: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setPadding(12, 28, 12, 28)
                if (isDanger) setTextColor(getColor(R.color.oneui_danger))
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            container.addView(tv)
        }

        val fullPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"

        if (!entry.isDir) {
            addOption("📤  Export to Phone") {
                pendingExportEntry = entry
                exportCreator.launch(entry.name)
            }
        }

        addOption("📋  Copy") {
            clipboard = ClipboardItem(fullPath, entry.name, entry.isDir, isMove = false)
            updateClipboardUI()
            Toast.makeText(this, "Navigate to destination and tap Paste Here", Toast.LENGTH_SHORT).show()
        }

        addOption("✂️  Move") {
            clipboard = ClipboardItem(fullPath, entry.name, entry.isDir, isMove = true)
            updateClipboardUI()
            Toast.makeText(this, "Navigate to destination and tap Paste Here", Toast.LENGTH_SHORT).show()
        }

        addOption("✏️  Rename") {
            promptRename(entry, fullPath)
        }

        addOption("🗑️  Delete", isDanger = true) {
            AlertDialog.Builder(this)
                .setTitle("Delete ${entry.name}?")
                .setMessage("This will permanently remove it from the NTFS drive.")
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ntfs?.deleteRecursive(fullPath, entry.isDir)
                        }
                        refreshStorageStats()
                        loadDir(currentPath)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun updateClipboardUI() {
        val clip = clipboard
        if (clip == null) {
            btnPaste.visibility = View.GONE
            btnCancelClip.visibility = View.GONE
        } else {
            btnPaste.visibility = View.VISIBLE
            btnCancelClip.visibility = View.VISIBLE
            btnPaste.text = if (clip.isMove) "Move Here (${clip.name})" else "Paste Here (${clip.name})"
        }
    }

    private fun executePaste() {
        val clip = clipboard ?: return
        val backend = ntfs ?: return
        val destPath = if (currentPath == "/") "/${clip.name}" else "$currentPath/${clip.name}"
        if (clip.fullPath == destPath) {
            Toast.makeText(this, "Same location", Toast.LENGTH_SHORT).show()
            return
        }

        @Suppress("DEPRECATION")
        val progress = ProgressDialog.show(this, if (clip.isMove) "Moving..." else "Copying...", clip.name, true, false)

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (clip.isMove) {
                    val rc = backend.rename(clip.fullPath, destPath)
                    if (rc == 0) true
                    else {
                        val copied = backend.copyRecursive(clip.fullPath, destPath, clip.isDir)
                        if (copied) backend.deleteRecursive(clip.fullPath, clip.isDir) else false
                    }
                } else {
                    backend.copyRecursive(clip.fullPath, destPath, clip.isDir)
                }
            }
            progress.dismiss()
            if (ok) {
                clipboard = null
                updateClipboardUI()
                refreshStorageStats()
                loadDir(currentPath)
                Toast.makeText(this@MainActivity, "Done", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Operation failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportUri(uri: Uri) {
        val backend = ntfs ?: return
        val fileName = getFileNameFromUri(uri) ?: "imported_file_${System.currentTimeMillis()}"
        val destPath = if (currentPath == "/") "/$fileName" else "$currentPath/$fileName"

        @Suppress("DEPRECATION")
        val progress = ProgressDialog.show(this, "Importing to USB...", fileName, true, false)

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    backend.importFile(destPath, input)
                } ?: false
            }
            progress.dismiss()
            if (ok) {
                refreshStorageStats()
                loadDir(currentPath)
                Toast.makeText(this@MainActivity, "Imported $fileName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleExportUri(uri: Uri, entry: NtfsEntry) {
        val backend = ntfs ?: return
        val srcPath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"

        @Suppress("DEPRECATION")
        val progress = ProgressDialog.show(this, "Exporting to Phone...", entry.name, true, false)

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { output ->
                    backend.exportFile(srcPath, entry.size, output)
                } ?: false
            }
            progress.dismiss()
            Toast.makeText(
                this@MainActivity,
                if (ok) "Exported ${entry.name}" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx >= 0) {
                return cursor.getString(nameIdx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun promptRename(entry: NtfsEntry, oldPath: String) {
        val input = EditText(this).apply { setText(entry.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.name) {
                    val newPath = if (currentPath == "/") "/$newName" else "$currentPath/$newName"
                    scope.launch {
                        val rc = withContext(Dispatchers.IO) { ntfs?.rename(oldPath, newPath) ?: -1 }
                        if (rc == 0) loadDir(currentPath)
                        else Toast.makeText(this@MainActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val target = if (currentPath == "/") "/$name" else "$currentPath/$name"
                    scope.launch {
                        withContext(Dispatchers.IO) { ntfs?.createDir(target) }
                        loadDir(currentPath)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        scope.cancel()
        ntfs?.unmount()
        blockDevice?.close()
    }

    class FileAdapter(
        private val items: List<NtfsEntry>,
        private val onClick: (NtfsEntry) -> Unit,
        private val onMoreClick: (NtfsEntry) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.itemIcon)
            val name: TextView = v.findViewById(R.id.itemName)
            val meta: TextView = v.findViewById(R.id.itemMeta)
            val btnMore: ImageButton = v.findViewById(R.id.btnMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            val dateStr = if (item.mtime > 0) dateFormat.format(Date(item.mtime)) else ""
            if (item.isDir) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.icon.setBackgroundResource(R.drawable.bg_icon_circle_folder)
                holder.meta.text = if (dateStr.isNotEmpty()) "Folder  •  $dateStr" else "Folder"
            } else {
                holder.icon.setImageResource(R.drawable.ic_file)
                holder.icon.setBackgroundResource(R.drawable.bg_icon_circle_file)
                val sizeStr = Formatter.formatShortFileSize(holder.itemView.context, item.size)
                holder.meta.text = if (dateStr.isNotEmpty()) "$sizeStr  •  $dateStr" else sizeStr
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onMoreClick(item); true }
            holder.btnMore.setOnClickListener { onMoreClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
