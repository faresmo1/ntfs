package com.otgfiles.fs

import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbMassStorageBlockDevice(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    var blockSize: Int = 512
        private set
    var totalBlocks: Long = 0
        private set
    val totalBytes: Long
        get() = blockSize.toLong() * totalBlocks

    private var tag = 1

    fun init(): Boolean {
        if (!connection.claimInterface(usbInterface, true)) return false
        scsiCommand(byteArrayOf(0x00, 0, 0, 0, 0, 0), null, false)
        val capBuf = ByteArray(8)
        if (!scsiCommand(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), capBuf, true)) {
            return false
        }
        val bb = ByteBuffer.wrap(capBuf).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val bSize = bb.int
        if (bSize <= 0) return false
        blockSize = bSize
        totalBlocks = lastLba + 1
        return true
    }

    @Synchronized
    fun readSectors(lba: Long, count: Int, dest: ByteArray, destOffset: Int): Boolean {
        val len = count * blockSize
        val cmd = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cmd.put(0x28.toByte())
        cmd.put(0)
        cmd.putInt(lba.toInt())
        cmd.put(0)
        cmd.putShort(count.toShort())
        cmd.put(0)
        val tmp = if (destOffset == 0 && dest.size >= len) dest else ByteArray(len)
        val ok = scsiCommand(cmd.array(), tmp, true, len)
        if (ok && tmp !== dest) {
            System.arraycopy(tmp, 0, dest, destOffset, len)
        }
        return ok
    }

    @Synchronized
    fun writeSectors(lba: Long, count: Int, src: ByteArray, srcOffset: Int): Boolean {
        val len = count * blockSize
        val cmd = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        cmd.put(0x2A.toByte())
        cmd.put(0)
        cmd.putInt(lba.toInt())
        cmd.put(0)
        cmd.putShort(count.toShort())
        cmd.put(0)
        val tmp = if (srcOffset == 0 && src.size >= len) src else ByteArray(len).also {
            System.arraycopy(src, srcOffset, it, 0, len)
        }
        return scsiCommand(cmd.array(), tmp, false, len)
    }

    private fun scsiCommand(cdb: ByteArray, data: ByteArray?, isIn: Boolean, dataLen: Int = data?.size ?: 0): Boolean {
        val currentTag = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355)
        cbw.putInt(currentTag)
        cbw.putInt(dataLen)
        cbw.put(if (isIn) 0x80.toByte() else 0x00.toByte())
        cbw.put(0)
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        while (cbw.position() < 31) cbw.put(0)

        if (connection.bulkTransfer(bulkOut, cbw.array(), 31, 5000) != 31) return false

        if (data != null && dataLen > 0) {
            var transferred = 0
            while (transferred < dataLen) {
                val chunk = minOf(16384, dataLen - transferred)
                val slice = ByteArray(chunk)
                if (!isIn) System.arraycopy(data, transferred, slice, 0, chunk)
                val ep = if (isIn) bulkIn else bulkOut
                val r = connection.bulkTransfer(ep, slice, chunk, 10000)
                if (r <= 0) return false
                if (isIn) System.arraycopy(slice, 0, data, transferred, r)
                transferred += r
            }
        }

        val csw = ByteArray(13)
        if (connection.bulkTransfer(bulkIn, csw, 13, 5000) != 13) return false
        val cswBb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cswBb.int
        val rTag = cswBb.int
        cswBb.int
        val status = cswBb.get().toInt()
        return sig == 0x53425355 && rTag == currentTag && status == 0
    }

    fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}
