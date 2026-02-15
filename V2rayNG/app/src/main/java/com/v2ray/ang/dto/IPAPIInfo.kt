package com.v2ray.ang.dto

data class IPAPIInfo(
    var ip: String? = null,
    var clientIp: String? = null,
    var ip_addr: String? = null,
    var query: String? = null,
    var country: String? = null,
    var country_name: String? = null,
    var country_code: String? = null,
    var countryCode: String? = null,
    var city: String? = null,
    var region: String? = null,
    var regionName: String? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var lat: Double? = null,
    var lon: Double? = null,
    var timezone: String? = null,
    var organization: String? = null,
    var isp: String? = null,
    var location: LocationBean? = null
) {
    data class LocationBean(
        var country_code: String? = null
    )

    fun resolvedIp(): String? = listOf(ip, clientIp, ip_addr, query).firstOrNull { !it.isNullOrBlank() }

    fun resolvedCountry(): String? = listOf(country_code, countryCode, country, country_name, location?.country_code).firstOrNull { !it.isNullOrBlank() }

    fun resolvedCity(): String? = listOf(city, region, regionName).firstOrNull { !it.isNullOrBlank() }

    fun resolvedLat(): Double? = latitude ?: lat

    fun resolvedLon(): Double? = longitude ?: lon
}