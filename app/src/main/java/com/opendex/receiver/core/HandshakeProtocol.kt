package com.opendex.receiver.core

/**
 * Our own wire protocol — deliberately simple and documented, not a copy of
 * any vendor's proprietary format:
 *
 *   1. Sender connects over TCP.
 *   2. Sender writes one UTF-8 JSON line (LF-terminated) describing itself:
 *        HandshakeRequest
 *   3. Receiver replies with one UTF-8 JSON line: HandshakeResponse
 *   4. After that, the socket becomes a raw binary stream of Frame records:
 *        [4-byte big-endian length][H.264 Annex-B NAL unit bytes]
 *      repeated until disconnect.
 *
 * A second, separate TCP connection (input channel) will carry touch /
 * mouse / keyboard events in Phase 3 — kept out of this file on purpose so
 * video and input concerns stay decoupled.
 */
data class HandshakeRequest(
    val deviceName: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val codec: String = "h264",
    val fps: Int = 60
)

data class HandshakeResponse(
    val accepted: Boolean,
    val reason: String? = null,
    val receiverWidth: Int = 0,
    val receiverHeight: Int = 0
)
