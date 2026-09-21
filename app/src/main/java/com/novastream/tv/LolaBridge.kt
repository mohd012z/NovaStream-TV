package com.novastream.tv

/** Redacted bridge from NovaStream /360 into a LOLA-compatible evidence packet. */
object LolaBridge {
    const val schema = "novastream-lola/1"
    data class Packet(val json: String, val route: String, val map: String, val recommendation: String)

    fun packet(item: PlaylistItem, trace: PlaybackTraceSnapshot): Packet {
        val decision = RecoveryBrain.decide(trace)
        val safePath = runCatching {
            val u = java.net.URI(item.streamUrl)
            java.net.URI(u.scheme, null, u.host, u.port, u.path, null, null).toString()
        }.getOrDefault(trace.sourceHost)
        val route = "SOURCE > " + trace.protocol + " > NETWORK > BUFFER > DECODER > RENDER > " + trace.health.name
        val map = "source:" + trace.sourceHealth.name +
            " | network:" + (if (trace.bandwidthEstimateBps > 0) trace.bandwidthEstimateBps.toString() + "bps" else "unknown") +
            " | buffer:" + trace.bufferedAheadMs + "ms" +
            " | decoder:" + (trace.videoCodecs ?: "unknown") +
            " | render:" + trace.videoWidth + "x" + trace.videoHeight +
            " | recovery:" + decision.action.name
        val recommendation = when (decision.cause) {
            RecoveryCause.NETWORK_STARVATION -> "Reduce selected bitrate; increase forward buffer only if starvation repeats."
            RecoveryCause.SOURCE_FAILURE -> "Rebuild the media pipeline; classify the source unhealthy if failures continue."
            RecoveryCause.DECODER_RENDER -> "Keep network settings; inspect codec/decoder compatibility and renderer fallback."
            RecoveryCause.NORMAL_BUFFERING -> "Observe only; short adaptive buffering needs no forced recovery."
            RecoveryCause.UNKNOWN -> "Collect more load, bandwidth, buffer and renderer evidence before intervening."
        }
        fun q(v: String?) = (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val json = "{" +
            "\"schema\":\"" + schema + "\"," +
            "\"source\":{\"host\":\"" + q(trace.sourceHost) + "\",\"safeUrl\":\"" + q(safePath) + "\",\"protocol\":\"" + q(trace.protocol) + "\"}," +
            "\"playback\":{\"health\":\"" + trace.health.name + "\",\"sourceHealth\":\"" + trace.sourceHealth.name + "\",\"stage\":\"" + trace.stage.name + "\"}," +
            "\"network\":{\"bandwidthBps\":" + trace.bandwidthEstimateBps + ",\"loads\":" + trace.completedLoads + ",\"loadErrors\":" + trace.loadErrorCount + "}," +
            "\"buffer\":{\"aheadMs\":" + trace.bufferedAheadMs + ",\"rebuffers\":" + trace.rebufferCount + ",\"totalRebufferMs\":" + trace.totalRebufferMs + ",\"maxRebufferMs\":" + trace.maxRebufferMs + "}," +
            "\"render\":{\"width\":" + trace.videoWidth + ",\"height\":" + trace.videoHeight + ",\"bitrate\":" + trace.videoBitrate + ",\"mime\":\"" + q(trace.videoMimeType) + "\",\"codec\":\"" + q(trace.videoCodecs) + "\",\"droppedFrames\":" + trace.droppedFrames + "}," +
            "\"recovery\":{\"action\":\"" + decision.action.name + "\",\"cause\":\"" + decision.cause.name + "\",\"reason\":\"" + q(decision.reason) + "\",\"attempts\":" + trace.retryCount + "}}"
        return Packet(json, route, map, recommendation)
    }
}
