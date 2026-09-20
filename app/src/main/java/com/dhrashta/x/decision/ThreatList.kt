package com.dhrashta.x.decision

import android.content.Context
import org.json.JSONObject

class ThreatList private constructor(
    private val certs: Set<String>,
    private val packages: Set<String>,
    private val domains: Set<String>,
) {
    fun contains(certSha256: String): Boolean = normalizeCert(certSha256) in certs
    fun containsPackage(pkg: String): Boolean = pkg.lowercase() in packages
    fun containsDomain(domain: String): Boolean = domain.lowercase().trimEnd('.') in domains

    companion object {
        fun load(context: Context, assetName: String = "threats.json"): ThreatList {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            return ThreatList(
                certs = root.stringSet("certs").map(::normalizeCert).toSet(),
                packages = root.stringSet("packages").map(String::lowercase).toSet(),
                domains = root.stringSet("domains").map { it.lowercase().trimEnd('.') }.toSet(),
            )
        }

        fun empty() = ThreatList(emptySet(), emptySet(), emptySet())

        private fun JSONObject.stringSet(key: String): Set<String> {
            val array = optJSONArray(key) ?: return emptySet()
            return buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }

        private fun normalizeCert(value: String): String =
            value.replace(":", "").replace(" ", "").uppercase()
    }
}
