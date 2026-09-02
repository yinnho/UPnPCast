package com.yinnho.upnpcast.internal.discovery

/**
 * A control endpoint of a UPnP service described by the device description
 */
data class ServiceInfo(
    val serviceType: String,
    val serviceId: String,
    val controlURL: String,
    val eventSubURL: String,
    val descriptorURL: String
)

/**
 * Remote device information - Simplified version
 */
data class RemoteDevice(
    val id: String,                    // Unique device identifier (using location URL)
    val displayName: String,           // Display name
    val address: String,               // Device address
    val manufacturer: String = "",     // Manufacturer
    val model: String = "",            // Model
    val locationUrl: String = "",      // Device description URL
    val services: List<ServiceInfo> = emptyList()  // Control endpoints
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteDevice) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "RemoteDevice(id='$id', name='$displayName', address='$address')"
    }
}
