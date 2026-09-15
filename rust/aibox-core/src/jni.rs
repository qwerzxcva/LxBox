//! JNI entry points called from Kotlin (System.loadLibrary("aibox_core")).
//!
//!  - snapshotFingerprint: the connection-snapshot delta path (phase 1).
//!  - routeCheck: the RSXM route-check engine (phase 2). Kotlin passes
//!    "<rulesJson>\n---\n<queryJson>" and gets back an explained decision
//!    as JSON, or null-ish "{}" when the payload could not be parsed —
//!    the caller falls back to a Kotlin-side "engine unavailable" note.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::ConnectionInfo;

#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_snapshotFingerprint(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let json: String = env
            .get_string(&input)
            .map_err(|e| e.to_string())?
            .into();
        let connections: Vec<ConnectionInfo> =
            serde_json::from_str(&json).unwrap_or_default();
        let (out, fp) = crate::snapshot_json_and_fingerprint(&connections);
        Ok(format!("[{fp},{out}]"))
    })()
    .unwrap_or_else(|_e| format!("[0,{{}}]"));

    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// The query shape the Kotlin side sends; mirrors rsxm_rules::CheckQuery.
#[derive(serde::Deserialize, Default)]
struct JniQuery {
    #[serde(default)]
    domain: Option<String>,
    #[serde(default)]
    destination_ip: Option<String>,
    #[serde(default)]
    source_ip: Option<String>,
    #[serde(default)]
    port: Option<u16>,
    #[serde(default)]
    source_port: Option<u16>,
    #[serde(default)]
    network: Option<String>,
    #[serde(default)]
    protocol: Option<String>,
    #[serde(default)]
    package: Option<String>,
    #[serde(default)]
    wifi_ssid: Option<String>,
    #[serde(default)]
    clash_mode: Option<String>,
    #[serde(default)]
    matched_rule_sets: Vec<String>,
}

#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_routeCheck(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
    query: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let rules_json: String = env.get_string(&rules).map_err(|e| e.to_string())?.into();
        let query_json: String = env.get_string(&query).map_err(|e| e.to_string())?.into();
        let checker = rsxm_rules::RouteChecker::from_json_array(&rules_json)?;
        let jq: JniQuery = serde_json::from_str(&query_json).map_err(|e| e.to_string())?;
        let q = rsxm_rules::CheckQuery {
            domain: jq.domain,
            destination_ip: jq.destination_ip.and_then(|s| s.parse().ok()),
            source_ip: jq.source_ip.and_then(|s| s.parse().ok()),
            port: jq.port,
            source_port: jq.source_port,
            network: jq.network,
            protocol: jq.protocol,
            package: jq.package,
            wifi_ssid: jq.wifi_ssid,
            clash_mode: jq.clash_mode,
            matched_rule_sets: jq.matched_rule_sets.into_iter().collect(),
        };
        let outcome = checker.check(&q);
        serde_json::to_string(&outcome).map_err(|e| e.to_string())
    })()
    .unwrap_or_else(|e| format!("{{\"error\":{}}}", serde_json::json!(e)));

    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
