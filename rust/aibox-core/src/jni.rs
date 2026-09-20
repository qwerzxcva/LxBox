//! JNI entry points called from Kotlin (System.loadLibrary("aibox_core")).
//!
//!  - snapshotFingerprint: the connection-snapshot delta path (phase 1).
//!  - routeCheck: the RSXM route-check engine (phase 2). Kotlin passes
//!    "<rulesJson>\n---\n<queryJson>" and gets back an explained decision
//!    as JSON, or null-ish "{}" when the payload could not be parsed —
//!    the caller falls back to a Kotlin-side "engine unavailable" note.
//!  - kernelStart/kernelStop: boots the full leader set (rules/dns/dialer/
//!    tun/power/security/stats) behind the Conductor, from one AppState
//!    JSON document (sliced by rsxm-config; the Conductor never reads it).
//!  - kernelDrainReports: JSON array of queued Conductor reports for the
//!    UI event stream.
//!  - statsRecord*/statsSnapshot: wire the rsxm-stats counters.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use rsxm_core::Module;
use rsxm_socks5::{DirectDialer, SocksServer};
use std::sync::{Arc, Mutex, OnceLock};

use crate::ConnectionInfo;

/// The process-wide kernel. Android loads this library once per process
/// (the :vpn process), so one global instance matches the Conductor model.
static KERNEL: OnceLock<Arc<std::sync::Mutex<rsxm_core::Conductor>>> = OnceLock::new();
static STATS: OnceLock<Arc<rsxm_stats::StatsModule>> = OnceLock::new();
static POWER: OnceLock<Arc<rsxm_power::PowerModule>> = OnceLock::new();
static DNS: OnceLock<Arc<rsxm_dns::DnsModule>> = OnceLock::new();
static TUN: OnceLock<Arc<rsxm_tun::TunModule>> = OnceLock::new();
static SOCKS: Mutex<Option<SocksServer>> = Mutex::new(None);
const RSXM_SOCKS_PORT: i32 = 7891;

fn kernel() -> Option<&'static Arc<std::sync::Mutex<rsxm_core::Conductor>>> {
    KERNEL.get()
}

fn with_kernel<R>(f: impl FnOnce(&mut rsxm_core::Conductor) -> R, default: R) -> R {
    match kernel() {
        Some(k) => {
            let mut guard = k.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            f(&mut guard)
        }
        None => default,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_snapshotFingerprint(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let json: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        let connections: Vec<ConnectionInfo> = serde_json::from_str(&json).unwrap_or_default();
        let (out, fp) = crate::snapshot_json_and_fingerprint(&connections);
        Ok(format!("[{fp},{out}]"))
    })()
    .unwrap_or_else(|_e| "[0,{}]".to_string());

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

// ---------------------------------------------------------------------------
// Kernel lifecycle + report bridge (rsxm 1.0)
// ---------------------------------------------------------------------------

fn jstr_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

fn to_jstring(env: &JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Boots the full leader set from one AppState-shaped JSON document. The
/// document is split by rsxm-config into opaque slices — this bridge only
/// marshals, it never interprets business fields. Returns "ok" or an error.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelStart(
    mut env: JNIEnv,
    _class: JClass,
    app_state_json: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let json = jstr_to_string(&mut env, &app_state_json);
        let doc: serde_json::Value =
            serde_json::from_str(&json).map_err(|e| format!("parse: {e}"))?;
        let envelope = rsxm_config::split(&doc);

        let stats = Arc::new(rsxm_stats::StatsModule::new());
        let _ = STATS.set(stats.clone());
        let power = Arc::new(rsxm_power::PowerModule::new());
        let _ = POWER.set(power.clone());
        let dns = Arc::new(rsxm_dns::DnsModule::new());
        let _ = DNS.set(dns.clone());
        let mut conductor = rsxm_core::Conductor::new();
        conductor.register(Arc::new(rsxm_rules::RulesModule::new()));
        conductor.register(Arc::new(rsxm_dns::DnsModule::new()));
        conductor.register(Arc::new(rsxm_dialer::DialerModule::new()));
        conductor.register(power);
        conductor.register(dns);
        conductor.register(Arc::new(rsxm_security::SecurityModule::new()));
        conductor.register(stats);
        let tun = Arc::new(rsxm_tun::TunModule::new(rsxm_tun::TunConfig::default()));
        let _ = TUN.set(tun.clone());
        conductor.register(tun);
        conductor.distribute(envelope);
        // The rsxm SOCKS5 server is the data path's local entry: HEV dials
        // it, the dialer dials out. Direct dialer for this milestone
        // (vless/reality dialer plugs behind the same DialFn seam).
        {
            let mut guard = SOCKS
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if guard.is_none() {
                match SocksServer::start(RSXM_SOCKS_PORT as u16, Arc::new(DirectDialer)) {
                    Ok(server) => {
                        *guard = Some(server);
                        conductor.reporter().send(rsxm_core::Report::new(
                            "rsxm",
                            rsxm_core::ReportKind::Info,
                            "socks5 listening on 127.0.0.1:7891",
                        ));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::AddrInUse => {
                        // Already running from a previous connect — fine.
                    }
                    Err(e) => return Err(format!("rsxm socks5 bind: {e}")),
                }
            }
        }
        let failures: Vec<String> = conductor
            .start_all()
            .into_iter()
            .filter_map(|(name, r)| r.err().map(|e| format!("{name}: {e}")))
            .collect();
        let _ = KERNEL.set(Arc::new(std::sync::Mutex::new(conductor)));
        Ok(if failures.is_empty() {
            "ok".into()
        } else {
            format!("partial; {}", failures.join("; "))
        })
    })()
    .unwrap_or_else(|e| format!("error: {e}"));
    to_jstring(&env, result)
}

/// Stops every leader. Safe to call repeatedly.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelStop(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    with_kernel(
        |k| {
            k.stop_all();
        },
        (),
    );
    {
        let mut guard = SOCKS
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(mut server) = guard.take() {
            server.shutdown();
        }
    }
    to_jstring(&env, "ok".into())
}

/// Host reports a screen / charging edge. Returns the power directive the
/// rest of the leaders should follow now ("run" | "throttle" | "deep_pause"),
/// or "off" when the kernel is not running. This is the only entry point the
/// Android screen-broadcast receiver needs: the conductor itself owns no
/// platform hooks, so without this bridge the power leader never saw a screen
/// event and the battery-saving throttle never engaged.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelPowerEvent(
    env: JNIEnv,
    _class: JClass,
    screen_on: jboolean,
    charging: jboolean,
) -> jstring {
    let out = match POWER.get() {
        Some(p) => {
            let directive = p.record_screen(screen_on != 0, charging != 0);
            directive.as_str().to_string()
        }
        None => "off".to_string(),
    };
    to_jstring(&env, out)
}

/// Asks the Rust DNS engine for a decision on one query name. This is the
/// shadow-DNS-to-real step: fakeip allocation, cache hits, hosts overrides
/// and rule-based reject/steer all happen here, in Rust. Returns
/// `{"action":"...","address":"..."?, "server":"..."?}` or `{}` when the
/// kernel is not running. Currently a decision-mirror (the Go engine still
/// executes); it lets the UI route-check page and future data-path
/// verifiers compare both stacks side by side.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelDnsDecide(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    qtype: JString,
) -> jstring {
    let name_s = jstr_to_string(&mut env, &name);
    let qtype_s = jstr_to_string(&mut env, &qtype);
    let out = match DNS.get() {
        Some(d) => {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            let qtype_opt = if qtype_s.is_empty() {
                None
            } else {
                Some(qtype_s.as_str())
            };
            let family = match qtype_opt.unwrap_or("A") {
                "AAAA" => rsxm_dns::Family::V6,
                _ => rsxm_dns::Family::V4,
            };
            let decision = d.with_engine(|engine| engine.decide(&name_s, family, None, now));
            let out_json = match decision {
                Some(rsxm_dns::Decision::AnswerFromCache { address, stale }) => {
                    serde_json::json!({"action": "cache", "address": address.to_string(), "stale": stale})
                }
                Some(rsxm_dns::Decision::AnswerFake { address }) => {
                    serde_json::json!({"action": "fakeip", "address": address.to_string()})
                }
                Some(rsxm_dns::Decision::AnswerHosts { address }) => {
                    serde_json::json!({"action": "hosts", "address": address.to_string()})
                }
                Some(rsxm_dns::Decision::NegativeCached) => {
                    serde_json::json!({"action": "negative"})
                }
                Some(rsxm_dns::Decision::Reject) => serde_json::json!({"action": "reject"}),
                Some(rsxm_dns::Decision::ForwardToServer { server }) => {
                    serde_json::json!({"action": "forward", "server": server})
                }
                Some(rsxm_dns::Decision::QueryUpstream) => {
                    serde_json::json!({"action": "upstream"})
                }
                Some(rsxm_dns::Decision::ReverseFake { name }) => {
                    serde_json::json!({"action": "reverse", "name": name})
                }
                None => serde_json::json!({}),
            };
            out_json.to_string()
        }
        None => "{}".to_string(),
    };
    to_jstring(&env, out)
}

/// Hands the tun fd to the Rust packet engine: rsxm-tun starts HEV with a
/// YAML that points its SOCKS5 hop at the rsxm server (the dialer serves
/// it), so the data path is tun -> HEV -> rsxm -> node, no sing-box. The
/// socks port must match the port the rsxm SOCKS5 server is bound to.
/// Returns "ok" or an error string; "no kernel" when kernelStart has not
/// run.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelTunStart(
    env: JNIEnv,
    _class: JClass,
    tun_fd: i32,
    socks_port: i32,
) -> jstring {
    let out = match TUN.get() {
        Some(t) => {
            t.set_tun_fd(tun_fd);
            // The SOCKS port is the rsxm server's fixed loopback port.
            let _ = socks_port;
            t.set_socks_port(RSXM_SOCKS_PORT as u16);
            match t.start() {
                Ok(()) => "ok".to_string(),
                Err(e) => format!("error: {e}"),
            }
        }
        None => "no kernel".to_string(),
    };
    to_jstring(&env, out)
}

/// Stops the HEV packet engine.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelTunStop(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = match TUN.get() {
        Some(t) => match t.stop() {
            Ok(()) => "ok".to_string(),
            Err(e) => format!("error: {e}"),
        },
        None => "no kernel".to_string(),
    };
    to_jstring(&env, out)
}

/// Drains queued Conductor reports as a JSON array:
/// `[{"module":"...","kind":"...","at":ms,"message":"..."}, ...]`.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelDrainReports(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = with_kernel(
        |k| {
            let reports: Vec<serde_json::Value> = k
                .drain_reports()
                .iter()
                .map(|r| {
                    serde_json::json!({
                        "module": r.module,
                        "kind": r.kind.as_str(),
                        "at": r.at_ms,
                        "message": r.message,
                    })
                })
                .collect();
            serde_json::to_string(&reports).unwrap_or_else(|_| "[]".into())
        },
        "[]".into(),
    );
    to_jstring(&env, out)
}

/// Aggregate health as JSON: `[{"module":"...","health":"up"}, ...]`.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_kernelHealth(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = with_kernel(
        |k| {
            let list: Vec<serde_json::Value> = k
                .health()
                .into_iter()
                .map(|(name, h)| serde_json::json!({ "module": name, "health": h.to_string() }))
                .collect();
            serde_json::to_string(&list).unwrap_or_else(|_| "[]".into())
        },
        "[]".into(),
    );
    to_jstring(&env, out)
}

// ---------------------------------------------------------------------------
// Stats bridge — the packet/dial path lives in Go today; Kotlin forwards
// the libbox counters it already sees, keeping rsxm-stats warm until the
// Rust data path takes over.
// ---------------------------------------------------------------------------

/// Records traffic into rsxm-stats: `op` ∈ {"open","close","reject"} for
/// connection events, or `record_bytes` with direction/up/down and outbound.
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_statsRecord(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    a: JString,
    b: JString,
) -> jstring {
    let kind = jstr_to_string(&mut env, &kind);
    let a = jstr_to_string(&mut env, &a);
    let b = jstr_to_string(&mut env, &b);
    let out = (|| -> Option<String> {
        let stats = STATS.get()?;
        match kind.as_str() {
            "open" => {
                stats.connection_opened();
                Some("ok".into())
            }
            "close" => {
                stats.connection_closed();
                Some("ok".into())
            }
            "reject" => {
                stats.connection_rejected();
                Some("ok".into())
            }
            "bytes" => {
                let n: u64 = a.parse().unwrap_or(0);
                stats.record_bytes(rsxm_stats::Direction::Up, &b, n);
                Some("ok".into())
            }
            other => Some(format!("unknown op: {other}")),
        }
    })()
    .unwrap_or_else(|| "no kernel".into());
    to_jstring(&env, out)
}

/// Stats snapshot as JSON (connections + per-outbound bytes).
#[no_mangle]
pub extern "system" fn Java_com_leadaxe_aibox_engine_rust_AiboxCore_statsSnapshot(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = STATS
        .get()
        .map(|s| serde_json::to_string(&s.snapshot()).unwrap_or_else(|_| "{}".into()))
        .unwrap_or_else(|| "{}".into());
    to_jstring(&env, out)
}
