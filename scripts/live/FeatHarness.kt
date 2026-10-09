import com.dhrashta.x.enforcement.relay.*
import java.io.File
fun main() {
    val f = LiveWindowFeatures()
    val pk = File("feat_pkts.tsv").readLines().map { it.split('\t').map(String::toLong) }
    val got = ArrayList<FloatArray>()
    // Collect every closed window by rolling at each window boundary
    var lastIdx = pk.first()[0] / 10_000
    val hist = ArrayList<FloatArray>()
    for (p in pk) {
        val idx = p[0] / 10_000
        f.record(1, p[0], p[1].toInt(), p[2] == 1L, p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6] == 1L)
    }
    // close final window and read the trailing history in pieces: re-run with roll at every boundary instead
    val g = LiveWindowFeatures()
    var cur = pk.first()[0] / 10_000
    for (p in pk) {
        val idx = p[0] / 10_000
        while (cur < idx) { cur++; g.roll(cur * 10_000).firstOrNull()?.let { got += it.windows.last() } ?: got.add(FloatArray(12)) }
        g.record(1, p[0], p[1].toInt(), p[2] == 1L, p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6] == 1L)
    }
    g.roll((cur + 1) * 10_000).firstOrNull()?.let { got += it.windows.last() }
    val ref = File("feat_ref.tsv").readLines().map { l -> l.split('\t').map(String::toDouble) }
    var maxRel = 0.0
    for (i in ref.indices) for (k in 0 until 12) {
        val a = ref[i][k]; val b = got[i][k].toDouble()
        maxRel = maxOf(maxRel, Math.abs(a - b) / maxOf(1e-6, Math.abs(a)))
    }
    println("windows ref=${ref.size} kotlin=${got.size} max relative diff=$maxRel ${if (ref.size == got.size && maxRel < 1e-5) "FEATURE PARITY PASS" else "FAIL"}")
}
