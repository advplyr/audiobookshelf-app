package com.audiobookshelf.app.dlna

import com.fasterxml.jackson.annotation.JsonIgnore
import net.mm2d.upnp.Device
import net.mm2d.upnp.Service

data class DlnaDevice(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val modelName: String?,
    val modelDescription: String?,
    val address: String,
    @JsonIgnore
    val device: Device? = null
) {
    private data class Services(val avTransport: Service?, val renderingControl: Service?, val connectionManager: Service?)

    private val services: Services = run {
        var avTransport: Service? = null
        var renderingControl: Service? = null
        var connectionManager: Service? = null

        fun scan(d: Device) {
            if (avTransport == null) avTransport = d.findServiceById("urn:upnp-org:serviceId:AVTransport") ?: d.findServiceByType("urn:schemas-upnp-org:service:AVTransport:1")
            if (renderingControl == null) renderingControl = d.findServiceById("urn:upnp-org:serviceId:RenderingControl") ?: d.findServiceByType("urn:schemas-upnp-org:service:RenderingControl:1")
            if (connectionManager == null) connectionManager = d.findServiceById("urn:upnp-org:serviceId:ConnectionManager") ?: d.findServiceByType("urn:schemas-upnp-org:service:ConnectionManager:1")
            d.deviceList.forEach { scan(it) }
        }

        device?.let { scan(it) }
        Services(avTransport, renderingControl, connectionManager)
    }

    @get:JsonIgnore val avTransportService: Service? get() = services.avTransport
    @get:JsonIgnore val renderingControlService: Service? get() = services.renderingControl
    @get:JsonIgnore val connectionManagerService: Service? get() = services.connectionManager
    @get:JsonIgnore val isValid: Boolean get() = avTransportService != null

    companion object {
        fun fromDevice(device: Device): DlnaDevice {
            return DlnaDevice(
                id = device.udn,
                name = device.friendlyName,
                manufacturer = device.manufacture,
                modelName = device.modelName,
                modelDescription = device.modelDescription,
                address = device.ipAddress,
                device = device
            )
        }
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "manufacturer" to manufacturer,
            "modelName" to modelName,
            "modelDescription" to modelDescription,
            "address" to address
        )
    }
}
