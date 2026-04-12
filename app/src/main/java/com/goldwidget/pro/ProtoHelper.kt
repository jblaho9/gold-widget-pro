package com.goldwidget.pro

/**
 * Minimal hand-rolled protobuf encoder/decoder for the cTrader Open API messages
 * we actually use. No library dependency needed.
 *
 * Wire types used here:
 *   0 = varint  (int32, int64, uint32, uint64, enum, bool)
 *   1 = 64-bit  (double, fixed64)
 *   2 = length-delimited (string, bytes, embedded message)
 */
object ProtoHelper {

    // ── Low-level encoding ────────────────────────────────────────────────

    fun encodeVarint(v: Long): ByteArray {
        val buf = mutableListOf<Byte>()
        var n = v
        while (n and 0x7F.toLong().inv() != 0L) {
            buf += ((n and 0x7F) or 0x80).toByte()
            n = n ushr 7
        }
        buf += (n and 0x7F).toByte()
        return buf.toByteArray()
    }

    private fun varintField(num: Int, v: Long): ByteArray =
        encodeVarint(((num shl 3) or 0).toLong()) + encodeVarint(v)

    private fun bytesField(num: Int, b: ByteArray): ByteArray =
        encodeVarint(((num shl 3) or 2).toLong()) + encodeVarint(b.size.toLong()) + b

    private fun stringField(num: Int, s: String): ByteArray =
        bytesField(num, s.toByteArray(Charsets.UTF_8))

    // ── Length-prefix framing (cTrader protocol requires 4-byte big-endian length) ──

    /** Prepend 4-byte big-endian length prefix to a protobuf message before sending. */
    fun frame(bytes: ByteArray): ByteArray {
        val len = bytes.size
        return byteArrayOf(
            (len shr 24).toByte(),
            (len shr 16).toByte(),
            (len shr 8).toByte(),
            len.toByte()
        ) + bytes
    }

    /** Strip 4-byte big-endian length prefix from a received frame. */
    fun unframe(bytes: ByteArray): ByteArray =
        if (bytes.size >= 4) bytes.copyOfRange(4, bytes.size) else bytes

    // ── ProtoMessage wrapper (payloadType=field1, payload=field2, msgId=field3) ──

    private fun wrap(payloadType: Int, inner: ByteArray, msgId: String): ByteArray {
        var m = varintField(1, payloadType.toLong())
        if (inner.isNotEmpty()) m += bytesField(2, inner)
        if (msgId.isNotEmpty()) m += stringField(3, msgId)
        return m
    }

    // ── Message builders ──────────────────────────────────────────────────

    /** ProtoOAApplicationAuthReq (2100) */
    fun appAuthReq(clientId: String, clientSecret: String): ByteArray =
        wrap(2100, stringField(1, clientId) + stringField(2, clientSecret), "1")

    /** ProtoOAAccountAuthReq (2102) */
    fun accountAuthReq(accessToken: String, accountId: Long): ByteArray =
        wrap(2102, stringField(1, accessToken) + varintField(2, accountId), "2")

    /** ProtoOAReconcileReq (2124) */
    fun reconcileReq(accountId: Long): ByteArray =
        wrap(2124, varintField(1, accountId), "3")

    /** ProtoOASubscribeSpotsReq (2126) */
    fun subscribeSpotsReq(accountId: Long, symbolId: Long): ByteArray =
        wrap(2126, varintField(1, accountId) + varintField(2, symbolId), "4")

    /** ProtoOAUnsubscribeSpotsReq (2128) */
    fun unsubscribeSpotsReq(accountId: Long, symbolId: Long): ByteArray =
        wrap(2128, varintField(1, accountId) + varintField(2, symbolId), "5")

    // ── Low-level decoding ────────────────────────────────────────────────

    /** Reads a varint from [b] starting at [off]. Returns (value, bytesConsumed). */
    fun readVarint(b: ByteArray, off: Int): Pair<Long, Int> {
        var r = 0L; var shift = 0; var i = off
        while (i < b.size) {
            val byte = b[i++].toLong() and 0xFF
            r = r or ((byte and 0x7F) shl shift)
            shift += 7
            if (byte and 0x80 == 0L) break
        }
        return r to (i - off)
    }

    /**
     * Parses all fields from a protobuf message, preserving repeated fields.
     * Returns list of (fieldNumber, value) where value is Long (varint/64-bit) or ByteArray.
     */
    fun parseAll(b: ByteArray): List<Pair<Int, Any>> {
        val out = mutableListOf<Pair<Int, Any>>()
        var i = 0
        while (i < b.size) {
            if (i >= b.size) break
            val (tag, tl) = readVarint(b, i); i += tl
            val fn = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> { val (v, vl) = readVarint(b, i); i += vl; out += fn to v }
                1 -> { // 64-bit little-endian (double, fixed64)
                    if (i + 8 > b.size) break
                    var v = 0L
                    for (s in 0..7) v = v or ((b[i + s].toLong() and 0xFF) shl (s * 8))
                    i += 8; out += fn to v
                }
                2 -> { // length-delimited
                    val (len, ll) = readVarint(b, i); i += ll
                    val end = i + len.toInt()
                    if (end > b.size) break
                    out += fn to b.copyOfRange(i, end); i = end
                }
                5 -> { i += 4 } // 32-bit, skip
                else -> break   // unknown wire type, bail
            }
        }
        return out
    }

    // ── High-level decoders ───────────────────────────────────────────────

    fun payloadType(msg: ByteArray): Int =
        (parseAll(msg).firstOrNull { it.first == 1 }?.second as? Long)?.toInt() ?: 0

    fun payload(msg: ByteArray): ByteArray? =
        parseAll(msg).firstOrNull { it.first == 2 }?.second as? ByteArray

    /**
     * Decode ProtoOAReconcileRes payload → list of TradeData.
     * Position proto layout:
     *   field 1 = positionId (int64/varint)
     *   field 2 = tradeData (embedded ProtoOATradeData)
     *   field 4 = swap (int64/varint, in cents)
     *   field 5 = price / entryPrice (double/64-bit)
     *   field 6 = stopLoss (double/64-bit)
     *   field 7 = takeProfit (double/64-bit)
     *   field 9 = commission (double/64-bit)
     * TradeData proto layout:
     *   field 1 = symbolId (int64/varint)
     *   field 2 = volume (int64/varint, in centilots: divide by 100 to get lots)
     *   field 3 = tradeSide enum (1=BUY, 2=SELL)
     *   field 4 = openTimestamp (int64/varint)
     */
    fun decodePositions(payload: ByteArray): List<TradeData> =
        parseAll(payload)
            .filter { it.first == 2 }
            .mapNotNull { (_, rawPos) ->
                try {
                    val pos    = parseAll(rawPos as ByteArray).groupBy { it.first }
                    val posId  = (pos[1]?.first()?.second as? Long)?.toString() ?: ""
                    val tdRaw  = pos[2]?.first()?.second as? ByteArray ?: return@mapNotNull null
                    val swapRaw= (pos[4]?.first()?.second as? Long) ?: 0L

                    val td    = parseAll(tdRaw).groupBy { it.first }
                    val symbolId  = (td[1]?.first()?.second as? Long) ?: 0L
                    val volumeRaw = (td[2]?.first()?.second as? Long) ?: 0L
                    val sideEnum  = (td[3]?.first()?.second as? Long) ?: 1L
                    val openTs    = (td[4]?.first()?.second as? Long) ?: System.currentTimeMillis()

                    TradeData(
                        positionId = posId,
                        symbol     = "XAUUSD",
                        side       = if (sideEnum == 2L) "SELL" else "BUY",
                        volumeLots = volumeRaw / 100.0,
                        entryPrice = doubleField(pos[5]),
                        swap       = swapRaw / 100.0,
                        commission = doubleField(pos[9]),
                        openTime   = openTs,
                        stopLoss   = doubleField(pos[6]),
                        takeProfit = doubleField(pos[7])
                    )
                } catch (e: Exception) { null }
            }

    /**
     * Decode ProtoOASpotEvent payload → live bid price.
     * field 3 = bid (uint64/varint) in price-points (price * 100 for XAUUSD with 2 digits)
     */
    fun decodeSpotBid(payload: ByteArray): Double {
        val rawBid = (parseAll(payload).firstOrNull { it.first == 3 }?.second as? Long) ?: return 0.0
        return if (rawBid > 10_000L) rawBid / 100.0 else rawBid.toDouble()
    }

    /** Extract symbolId of first position for spot subscription. */
    fun firstSymbolId(payload: ByteArray): Long {
        val posBytes = parseAll(payload).firstOrNull { it.first == 2 }?.second as? ByteArray
            ?: return 0L
        val tdBytes = parseAll(posBytes).firstOrNull { it.first == 2 }?.second as? ByteArray
            ?: return 0L
        return (parseAll(tdBytes).firstOrNull { it.first == 1 }?.second as? Long) ?: 0L
    }

    /** Parse error description from ProtoOAErrorRes payload (field 3). */
    fun decodeErrorDesc(payload: ByteArray): String {
        val bytes = parseAll(payload).firstOrNull { it.first == 3 }?.second as? ByteArray
            ?: return "unknown"
        return String(bytes, Charsets.UTF_8)
    }

    private fun doubleField(entries: List<Pair<Int, Any>>?): Double {
        val raw = entries?.firstOrNull()?.second as? Long ?: return 0.0
        return java.lang.Double.longBitsToDouble(raw)
    }
}
