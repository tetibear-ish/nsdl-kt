package com.a2z.nsdl.platform

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.software.OperatingSystem
import com.a2z.nsdl.sim.WorkScope

/**
 * Runs an [OperatingSystem] as part of a device's lifecycle. Register it after the protocol
 * services it depends on, so they are running before the software boots.
 */
class OperatingSystemService(private val os: OperatingSystem) : DeviceService {
    override val id get() = os.id
    override fun start(scope: WorkScope) = os.boot(scope)
    override fun stop() = os.shutdown()
    override fun snapshot() = os.snapshot()
}
