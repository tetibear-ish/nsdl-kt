package com.a2z.nsdl.device

import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.sim.WorkScope

/**
 * A protocol or application component hosted by a device (DHCP client, DHCP server, ...).
 * Started after boot with that run's [WorkScope]; stopped when power is removed.
 */
interface DeviceService : Inspectable {
    fun start(scope: WorkScope)
    fun stop()
}
