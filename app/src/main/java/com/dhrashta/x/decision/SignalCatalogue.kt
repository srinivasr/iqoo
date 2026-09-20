package com.dhrashta.x.decision

data class Signal(val id: String, val weight: Int, val description: String)

object SignalCatalogue {
    val A1 = Signal("A1", 15, "Service enabled, not accessibility tool")
    val A2 = Signal("A2", 10, "Can retrieve window content")
    val A3 = Signal("A3", 10, "Can perform gestures")
    val A4 = Signal("A4", 10, "Can filter key events")
    val A5 = Signal("A5", 5, "Listens to all packages")
    val A6 = Signal("A6", 15, "Enabled within 10 min of install")
    val B1 = Signal("B1", 15, "Sideloaded")
    val B4 = Signal("B4", 50, "Cert SHA-256 in threat list")
    val B5 = Signal("B5", 15, "No launcher icon")
    val B6 = Signal("B6", 20, "Name mimics bank/govt")
    val C1 = Signal("C1", 10, "SYSTEM_ALERT_WINDOW requested")
    val C5 = Signal("C5", 5, "QUERY_ALL_PACKAGES")
    val D1 = Signal("D1", 10, "Wireless debugging on recently")
    val D2 = Signal("D2", 20, "New work profile appeared")
    val E4 = Signal("E4", 20, "Network anomaly score high")
    val E5 = Signal("E5", 20, "MLP predicts malicious behavior")
    val N1 = Signal("N1", -30, "isAccessibilityTool + Play install")
    val N2 = Signal("N2", -100, "On user allow-list")
    val N3 = Signal("N3", -40, "System pre-installed app")
}
