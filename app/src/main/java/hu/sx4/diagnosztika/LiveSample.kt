package hu.sx4.diagnosztika

/** Numeric export accompanies the existing human-readable decoding and exact data bytes. */
data class LiveSample(val pid: Int, val data: List<Int>, val reading: String, val value: Double?, val unit: String) {
    companion object {
        fun decode(pid: Int, data: List<Int>): LiveSample {
            val reading = Obd.reading(pid, data) // validates supported PID and exact data length first
            val a = data[0].toDouble()
            val ab = a * 256 + data.getOrElse(1) { 0 }
            val numeric: Pair<Double?, String> = when (pid) {
                0x04, 0x11, 0x2F, 0x45 -> a * 100 / 255 to "%"
                0x05, 0x0F, 0x46 -> a - 40 to "degC"
                0x06, 0x07 -> (a - 128) * 100 / 128 to "%"
                0x0B, 0x33 -> a to "kPa"
                0x0C -> ab / 4 to "rpm"
                0x0D -> a to "km/h"
                0x0E -> a / 2 - 64 to "deg"
                0x10 -> ab / 100 to "g/s"
                0x14, 0x15 -> a / 200 to "V"
                0x24, 0x25 -> ab * 2 / 65536 to "lambda"
                0x1F -> ab to "s"
                0x21, 0x31 -> ab to "km"
                0x30 -> a to "count"
                0x3C -> ab / 10 - 40 to "degC"
                0x42 -> ab / 1000 to "V"
                0x43 -> ab * 100 / 255 to "%"
                0x44 -> ab / 32768 to "lambda"
                0x4D, 0x4E -> ab to "min"
                else -> null to "" // status/bit fields remain losslessly available in reading/data
            }
            return LiveSample(pid, data.toList(), reading, numeric.first, numeric.second)
        }
    }
}
