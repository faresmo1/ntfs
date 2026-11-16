package com.otgfiles.fs

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class NtfsEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)

class NtfsBackend(private val blockDev: UsbMassStorageBlockDevice) {
    companion object {
        init {
            System.loadLibrary("ntfsjni")
        }
    }

    private var volPtr: Long = 0

    fun mount(readOnly: Boolean = false): Int {
        val res = nativeMount(this, blockDev.totalBytes, readOnly)
        if (res > 0) {
            volPtr = res
            return 0
        }
        return res.toInt()
    }

    fun unmount() {
        if (volPtr != 0L) {
            nativeUnmount(volPtr)
            volPtr = 0
        }
    }

    fun totalSpace(): Long = if (volPtr != 0L) nativeTotal(volPtr) else 0L
    fun freeSpace(): Long = if (volPtr != 0L) nativeFree(volPtr) else 0L

    fun list(path: String): List<NtfsEntry> {
        if (volPtr == 0L) return emptyList()
        val raw = nativeList(volPtr, path.toByteArray(StandardCharsets.UTF_8)) ?: return emptyList()
        return raw.mapNotNull { s ->
            val parts = s.split('\u001F')
            if (parts.size >= 4) {
                NtfsEntry(
                    name = parts[0],
                    isDir = parts[1] == "1",
                    size = parts[2].toLongOrNull() ?: 0L,
                    mtime = parts[3].toLongOrNull() ?: 0L
                )
            } else null
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun createDir(path: String): Int =
        if (volPtr != 0L) nativeCreate(volPtr, path.toByteArray(StandardCharsets.UTF_8), true) else -1

    fun delete(path: String): Int =
        if (volPtr != 0L) nativeUnlink(volPtr, path.toByteArray(StandardCharsets.UTF_8)) else -1

    fun rename(fromPath: String, toPath: String): Int =
        if (volPtr != 0L) nativeRename(
            volPtr,
            fromPath.toByteArray(StandardCharsets.UTF_8),
            toPath.toByteArray(StandardCharsets.UTF_8)
        ) else -1

    // Import stream from Phone into NTFS drive
    fun importFile(destNtfsPath: String, input: InputStream, onProgress: (Long) -> Unit = {}): Boolean {
        if (volPtr == 0L) return false
        val h = nativeOpen(volPtr, destNtfsPath.toByteArray(StandardCharsets.UTF_8), write = true, truncate = true)
        if (h == 0L) return false
        try {
            val buf = ByteArray(256 * 1024)
            var offset = 0L
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                val written = nativeWrite(h, offset, buf, read)
                if (written != read) return false
                offset += written
                onProgress(offset)
            }
            return true
        } finally {
            nativeClose(h)
        }
    }

    // Export file from NTFS drive to Phone stream
    fun exportFile(srcNtfsPath: String, totalSize: Long, output: OutputStream, onProgress: (Long) -> Unit = {}): Boolean {
        if (volPtr == 0L) return false
        val h = nativeOpen(volPtr, srcNtfsPath.toByteArray(StandardCharsets.UTF_8), write = false, truncate = false)
        if (h == 0L) return false
        try {
            val buf = ByteArray(256 * 1024)
            var offset = 0L
            while (offset < totalSize) {
                val toRead = minOf(buf.size.toLong(), totalSize - offset).toInt()
                val read = nativeRead(h, offset, buf, toRead)
                if (read <= 0) break
                output.write(buf, 0, read)
                offset += read
                onProgress(offset)
            }
            output.flush()
            return offset == totalSize
        } finally {
            nativeClose(h)
        }
    }

    // Copy file or folder recursively inside NTFS drive
    fun copyRecursive(srcPath: String, destPath: String, isDir: Boolean): Boolean {
        if (volPtr == 0L || srcPath == destPath) return false
        if (isDir) {
            if (destPath.startsWith("$srcPath/")) return false
            createDir(destPath)
            val children = list(srcPath)
            for (child in children) {
                val cSrc = if (srcPath == "/") "/${child.name}" else "$srcPath/${child.name}"
                val cDest = if (destPath == "/") "/${child.name}" else "$destPath/${child.name}"
                if (!copyRecursive(cSrc, cDest, child.isDir)) return false
            }
            return true
        } else {
            val hSrc = nativeOpen(volPtr, srcPath.toByteArray(StandardCharsets.UTF_8), write = false, truncate = false)
            if (hSrc == 0L) return false
            val hDst = nativeOpen(volPtr, destPath.toByteArray(StandardCharsets.UTF_8), write = true, truncate = true)
            if (hDst == 0L) {
                nativeClose(hSrc)
                return false
            }
            try {
                val buf = ByteArray(256 * 1024)
                var offset = 0L
                while (true) {
                    val r = nativeRead(hSrc, offset, buf, buf.size)
                    if (r <= 0) break
                    val w = nativeWrite(hDst, offset, buf, r)
                    if (w != r) return false
                    offset += w
                }
                return true
            } finally {
                nativeClose(hSrc)
                nativeClose(hDst)
            }
        }
    }

    // Delete file or folder recursively
    fun deleteRecursive(path: String, isDir: Boolean): Boolean {
        if (isDir) {
            for (child in list(path)) {
                val cPath = if (path == "/") "/${child.name}" else "$path/${child.name}"
                if (!deleteRecursive(cPath, child.isDir)) return false
            }
        }
        return delete(path) == 0
    }

    // Called from JNI
    fun devRead(offset: Long, buf: ByteArray, len: Int): Int {
        val bs = blockDev.blockSize.toLong()
        val startLba = offset / bs
        val endLba = (offset + len - 1) / bs
        val sectorCount = (endLba - startLba + 1).toInt()
        val alignedBuf = ByteArray(sectorCount * blockDev.blockSize)
        if (!blockDev.readSectors(startLba, sectorCount, alignedBuf, 0)) return -1
        val headSkip = (offset % bs).toInt()
        System.arraycopy(alignedBuf, headSkip, buf, 0, len)
        return len
    }

    // Called from JNI
    fun devWrite(offset: Long, buf: ByteArray, len: Int): Int {
        val bs = blockDev.blockSize.toLong()
        val startLba = offset / bs
        val endLba = (offset + len - 1) / bs
        val sectorCount = (endLba - startLba + 1).toInt()
        val alignedBuf = ByteArray(sectorCount * blockDev.blockSize)
        val headSkip = (offset % bs).toInt()
        if (headSkip != 0 || (offset + len) % bs != 0L) {
            if (!blockDev.readSectors(startLba, sectorCount, alignedBuf, 0)) return -1
        }
        System.arraycopy(buf, 0, alignedBuf, headSkip, len)
        if (!blockDev.writeSectors(startLba, sectorCount, alignedBuf, 0)) return -1
        return len
    }

    private external fun nativeMount(self: NtfsBackend, size: Long, ro: Boolean): Long
    private external fun nativeUnmount(v: Long)
    private external fun nativeTotal(v: Long): Long
    private external fun nativeFree(v: Long): Long
    private external fun nativeList(v: Long, path: ByteArray): Array<String>?
    private external fun nativeCreate(v: Long, path: ByteArray, dir: Boolean): Int
    private external fun nativeUnlink(v: Long, path: ByteArray): Int
    private external fun nativeRename(v: Long, from: ByteArray, to: ByteArray): Int
    private external fun nativeOpen(v: Long, path: ByteArray, write: Boolean, truncate: Boolean): Long
    private external fun nativeRead(hv: Long, off: Long, buf: ByteArray, len: Int): Int
    private external fun nativeWrite(hv: Long, off: Long, buf: ByteArray, len: Int): Int
    private external fun nativeClose(hv: Long)
}
