package com.nanocomm.nanosmart.eventos

object AudibleAlarmPolicy {
    private val audibleEventCodes = setOf(
        "100", "110", "120",
        "130", "131", "132", "133", "134",
        "135", "136", "137", "138", "139"
    )

    fun shouldSound(data: Map<String, String>): Boolean {
        if (data["type"]?.uppercase() != "ALERT") return false
        return data["eventCode"]?.trim() in audibleEventCodes
    }
}
