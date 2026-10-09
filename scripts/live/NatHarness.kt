import com.dhrashta.x.enforcement.relay.*
import java.net.*

fun main() {
    val fromBridge = DatagramSocket(InetSocketAddress("127.0.0.1", 47001))
    val toBridge = DatagramSocket()
    val bridgeAddr = InetSocketAddress("127.0.0.1", 47002)
    val features = LiveWindowFeatures(windowMillis = 2_000)
    var outPk = 0; var inPk = 0; var outB = 0L; var inB = 0L
    val nat = UserspaceNat(
        writeToTun = { p -> synchronized(toBridge) { toBridge.send(DatagramPacket(p, p.size, bridgeAddr)) } },
        protectTcp = { true }, protectUdp = { true },
        observer = { uid, ts, len, outgoing, rip, rport, proto, syn ->
            synchronized(features) { if (outgoing) { outPk++; outB += len } else { inPk++; inB += len } }
            features.record(uid, ts, len, outgoing, rip, rport, proto, syn)
        },
        target = { _, port -> InetSocketAddress("127.0.0.1", port) },
    )
    nat.start()
    Thread {
        while (true) {
            Thread.sleep(2_000)
            features.roll(System.currentTimeMillis()).forEach { s ->
                val last = s.windows.last()
                println("WINDOW uid=${s.uid} active=${s.activeWindows} last=${last.joinToString(",") { "%.2f".format(it) }}")
            }
            synchronized(features) { println("STATS out=$outPk/$outB in=$inPk/$inB flows=${nat.activeFlows}") }
        }
    }.apply { isDaemon = true; start() }
    val buf = ByteArray(65_535)
    while (true) {
        val dp = DatagramPacket(buf, buf.size)
        fromBridge.receive(dp)
        nat.handleOutbound(buf, dp.length, 1)
    }
}
