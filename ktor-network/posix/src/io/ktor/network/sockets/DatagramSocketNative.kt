/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.io.IOException
import platform.posix.*
import kotlin.coroutines.*

internal class DatagramSocketNative(
    private val descriptor: Int,
    val selector: SelectorManager,
    val selectable: Selectable,
    private val remote: SocketAddress?,
    parent: CoroutineContext = EmptyCoroutineContext
) : BoundDatagramSocket, ConnectedDatagramSocket, CoroutineScope {
    private val _context: CompletableJob = Job(parent[Job])

    override val coroutineContext: CoroutineContext = parent + Dispatchers.Unconfined + _context

    override val socketContext: Job
        get() = _context

    override val localAddress: SocketAddress
        get() = getLocalAddress(descriptor).toSocketAddress()

    override val remoteAddress: SocketAddress
        get() = getRemoteAddress(descriptor).toSocketAddress()

    private val sender: SendChannel<Datagram> = DatagramSendChannel(descriptor, this, remote)

    override fun toString(): String = "DatagramSocketNative(descriptor=$descriptor)"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val receiver: ReceiveChannel<Datagram> = produce {
        try {
            while (true) {
                val received = readDatagram()
                channel.send(received)
            }
        } catch (_: ClosedSendChannelException) {
        } catch (cause: IOException) {
        } catch (cause: PosixException) {
        }
    }

    override val incoming: ReceiveChannel<Datagram>
        get() = receiver

    override val outgoing: SendChannel<Datagram>
        get() = sender

    override fun close() {
        receiver.cancel()
        _context.complete()
        _context.invokeOnCompletion {
            ktor_shutdown(descriptor, ShutdownCommands.Both)
            // Descriptor is closed by the selector manager
            selector.notifyClosed(selectable)
        }
        sender.close()
    }

    private suspend fun readDatagram(): Datagram {
        while (true) {
            val datagram = tryReadDatagram()
            if (datagram != null) return datagram
            selector.select(selectable, SelectInterest.READ)
        }
    }

    @OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
    private fun tryReadDatagram(): Datagram? = memScoped {
        val clientAddress = alloc<sockaddr_storage>()
        val clientAddressLength: UIntVarOf<UInt> = alloc()
        clientAddressLength.value = sizeOf<sockaddr_storage>().convert()

        val buffer = BytePacketBuilder()

        try {
            val count = buffer.write { memory, startIndex, endIndex ->
                val bufferStart = memory + startIndex
                val size = endIndex - startIndex
                val bytesRead = ktor_recvfrom(
                    descriptor,
                    bufferStart,
                    size.convert(),
                    0,
                    clientAddress.ptr.reinterpret(),
                    clientAddressLength.ptr
                ).toLong()

                when (bytesRead) {
                    0L -> throw IOException("Failed reading from closed socket")
                    -1L -> {
                        if (isWouldBlockError(getSocketError())) return@write 0
                        throw PosixException.forSocketError()
                    }

                    else -> bytesRead
                }
            }

            if (count <= 0) return null
            val address = clientAddress.reinterpret<sockaddr>().toNativeSocketAddress()

            return Datagram(
                buffer.build(),
                address.toSocketAddress()
            )
        } finally {
            buffer.close()
        }
    }
}
