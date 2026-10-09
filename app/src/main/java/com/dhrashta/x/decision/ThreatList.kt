package com.dhrashta.x.decision

import android.content.Context
import org.json.JSONObject

/**
 * Local indicator list. Every file must say where its entries came from ("meta"); the release build
 * ships an EMPTY list because no vetted feed is bundled. Debug builds add clearly-labelled demo
 * entries from src/debug/assets for testing only.
 */
class ThreatList private constructor(
    private val certs: Set<String>,
    private val packages: Set<String>,
    private val domains: Set<String>,
    val source: String = "none",
) {
    data class Info(val entries: Int, val source: String)

    val size: Int get() = certs.size + packages.size + domains.size

    fun contains(certSha256: String): Boolean = normalizeCert(certSha256) in certs
    fun containsPackage(pkg: String): Boolean = pkg.lowercase() in packages
    fun containsDomain(domain: String): Boolean = domain.lowercase().trimEnd('.') in domains

    companion object {
        fun load(context: Context, assetName: String = "threats.json"): ThreatList {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val meta = root.optJSONObject("meta")
            return ThreatList(
                certs = root.stringSet("certs").map(::normalizeCert).toSet(),
                packages = root.stringSet("packages").map(String::lowercase).toSet(),
                domains = root.stringSet("domains").map { it.lowercase().trimEnd('.') }.toSet(),
                source = meta?.optString("source")?.takeIf(String::isNotBlank) ?: "unlabelled",
            )
        }

        fun info(context: Context): Info = load(context).let { Info(it.size, it.source) }

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
