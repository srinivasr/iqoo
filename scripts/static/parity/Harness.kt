import com.dhrashta.x.ai.staticml.*
import java.io.File
fun main(args: Array<String>) {
    val drebin = File(args[0]).readLines()
    val ex = StaticFeatureExtractor(drebin)
    var allOk = true
    File(args[1]).readLines().forEach { line ->
        val c = line.split('\t')
        fun l(i: Int) = if (c[i].isEmpty()) emptyList() else c[i].split('|')
        val facts = ApkFacts(c[1], l(3), l(4), l(5), l(6), c[2] == "true", File(c[0]))
        val t0 = System.nanoTime()
        val r = ex.extract(facts)
        val ms = (System.nanoTime() - t0) / 1e6
        val expected = l(7).toSet()
        val missing = expected - r.fired
        val extra = r.fired - expected
        println("${File(c[0]).name}: fired=${r.fired.size} expected=${expected.size} dex=${r.dexFiles} strings=${r.strings} methods=${r.methods} ${"%.0f".format(ms)}ms missing=$missing extra=$extra")
        if (missing.isNotEmpty() || extra.isNotEmpty()) allOk = false
    }
    println(if (allOk) "PARITY PASS" else "PARITY FAIL")
}
