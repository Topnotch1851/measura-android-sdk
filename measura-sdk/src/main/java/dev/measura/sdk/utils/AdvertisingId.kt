package dev.measura.sdk.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal object AdvertisingId {

    private const val ACTION = "com.google.android.gms.ads.identifier.service.START"
    private const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    private const val DESCRIPTOR =
        "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"

    private const val TXN_GET_ID = 1
    private const val TXN_GET_LAT = 2

    private const val BIND_TIMEOUT_SECONDS = 10L

    private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"

    data class Info(val id: String, val limitAdTracking: Boolean)

    fun fetch(context: Context, logger: Logger): Info? {
        val connection = BlockingServiceConnection()
        val intent = Intent(ACTION).setPackage(PLAY_SERVICES_PACKAGE)

        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            logger.debug("Advertising ID: bindService refused (${e.javaClass.simpleName})")
            false
        }
        if (!bound) {
            logger.debug("Advertising ID unavailable: no Play Services identifier service")
            runCatching { context.unbindService(connection) }
            return null
        }

        return try {
            val binder = connection.take() ?: return null
            val id = binder.transactString(TXN_GET_ID)
            val lat = binder.transactBoolean(TXN_GET_LAT)

            when {
                id.isNullOrBlank() -> null
                id == ZERO_ID -> {
                    logger.debug("Advertising ID is the all-zero placeholder; not sending it")
                    null
                }
                lat -> {
                    logger.debug("Limit Ad Tracking is on; advertising ID not collected")
                    null
                }
                else -> Info(id, false)
            }
        } catch (e: Throwable) {
            logger.debug("Advertising ID read failed (${e.javaClass.simpleName})")
            null
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun IBinder.transactString(code: Int): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            transact(code, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun IBinder.transactBoolean(code: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            transact(code, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: RemoteException) {
            true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private class BlockingServiceConnection : ServiceConnection {
        private val queue = LinkedBlockingQueue<IBinder>(1)
        @Volatile private var taken = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null) queue.offer(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) { /* nothing to do */ }

        fun take(): IBinder? {
            check(!taken) { "BlockingServiceConnection is single use" }
            taken = true
            return queue.poll(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}
