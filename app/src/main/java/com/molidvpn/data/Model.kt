package com.molidvpn.data

data class Server(
    val id: String,
    val name: String,
    val country: String,
    val flag: String,
    val ip: String,
    val seedPing: Int,
    val badge: String? = null,
) {
    val title: String get() = name + "، " + country
}

object Servers {
    val all: List<Server> = listOf(
        Server("ams", "آمستردام", "هلند", "🇳🇱", "185.107.56.14", 96, "بهینه"),
        Server("fra", "فرانکفورت", "آلمان", "🇩🇪", "89.163.145.22", 104),
        Server("hel", "هلسینکی", "فنلاند", "🇫🇮", "95.216.14.9", 118),
        Server("ist", "استانبول", "ترکیه", "🇹🇷", "185.125.190.6", 58),
        Server("dxb", "دبی", "امارات", "🇦🇪", "94.200.31.77", 44),
    )
    fun byId(id: String?): Server = all.firstOrNull { it.id == id } ?: all.first()
}
