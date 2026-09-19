package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.defaultDnsServers
import com.leadaxe.aibox.app.OutboundProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

class ConfigDiagnosticTest {
    @Test
    fun dumpRealistic() {
        val node = OutboundProfile(
            id = "n1", name = "HK 01", type = "vless",
            config = """{"type":"vless","server":"hk.example.com","server_port":443,"uuid":"u"}""",
        )
        val state = AppState(
            dnsServers = defaultDnsServers(),
            outbounds = listOf(node),
            selectedOutbound = "node-n1",
            enableFakeIp = true,
        )
        val config = ConfigCompiler.compile(state, File("/tmp/aibox-test-rulesets").apply { mkdirs() })
        File("/tmp/aibox-realistic.json").writeText(
            Json { prettyPrint = true }.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(), config,
            ),
        )
    }
}
