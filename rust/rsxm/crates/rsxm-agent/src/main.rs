//! RSXM agent: the executable that boots the micro-kernel scheduler.
//!
//! During the migration this binary coexists with the sing-box core. It
//! loads a rule set (JSON, same shape the app already produces), builds the
//! routing table, starts the module set, and serves a line-oriented console
//! for health and route probing. On Android the same library API is called
//! through JNI; the CLI is for development and CI verification.
//!
//! Usage:
//!   rsxm-agent --rules rules.json          boot and print module health
//!   rsxm-agent --rules rules.json --route example.com [--package com.app]
//!                                          dry-run one routing decision
//!   rsxm-agent --check --rules compiled.json --route example.com [--port 443]
//!                                          replay the *compiled* sing-box
//!                                          rule table (route-check engine)

use rsxm_core::{Conductor, ConfigEnvelope, Health};
use rsxm_dialer::DialerModule;
use rsxm_dns::DnsModule;
use rsxm_power::PowerModule;
use rsxm_rules::{Query, RulesModule};
use rsxm_security::SecurityModule;
use rsxm_stats::StatsModule;
use rsxm_tun::{TunConfig, TunModule};
use std::sync::Arc;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut rules_path: Option<String> = None;
    let mut route_domain: Option<String> = None;
    let mut route_package: Option<String> = None;
    let mut route_ip: Option<String> = None;
    let mut route_port: Option<u16> = None;
    let mut route_network: Option<String> = None;
    let mut check_mode = false;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--rules" => {
                i += 1;
                rules_path = args.get(i).cloned();
            }
            "--route" => {
                i += 1;
                route_domain = args.get(i).cloned();
            }
            "--package" => {
                i += 1;
                route_package = args.get(i).cloned();
            }
            "--ip" => {
                i += 1;
                route_ip = args.get(i).cloned();
            }
            "--port" => {
                i += 1;
                route_port = args.get(i).and_then(|s| s.parse().ok());
            }
            "--network" => {
                i += 1;
                route_network = args.get(i).cloned();
            }
            "--check" => {
                check_mode = true;
            }
            other => {
                eprintln!("unknown argument: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }

    // Route-check mode replays a compiled sing-box table; it does not need
    // the module lifecycle at all.
    if check_mode {
        let path = rules_path.unwrap_or_else(|| {
            eprintln!("--check needs --rules <compiled.json>");
            std::process::exit(2);
        });
        let text = std::fs::read_to_string(&path).unwrap_or_else(|err| {
            eprintln!("cannot read rules file: {err}");
            std::process::exit(1);
        });
        let checker = match rsxm_rules::RouteChecker::from_json_array(&text) {
            Ok(c) => c,
            Err(err) => {
                eprintln!("rules parse error: {err}");
                std::process::exit(1);
            }
        };
        let q = rsxm_rules::CheckQuery {
            domain: route_domain,
            destination_ip: route_ip.and_then(|s| s.parse().ok()),
            port: route_port,
            package: route_package,
            network: route_network,
            ..Default::default()
        };
        let outcome = checker.check(&q);
        println!(
            "{}",
            serde_json::to_string_pretty(&outcome).unwrap_or_else(|_| "{}".into())
        );
        return;
    }

    // ---- Boot the one-super-many-strong kernel --------------------------
    // The Conductor owns lifecycle and supervision only; every leader gets
    // its own opaque slice. Registration order is irrelevant: start order is
    // derived from declared dependencies (tun waits for the dialer, dns
    // waits for rules).
    let rules_module = Arc::new(RulesModule::new());
    let mut conductor = Conductor::new();
    conductor.register(rules_module.clone() as Arc<dyn rsxm_core::Module>);
    conductor.register(Arc::new(DnsModule::new()));
    conductor.register(Arc::new(DialerModule::new()));
    conductor.register(Arc::new(PowerModule::new()));
    conductor.register(Arc::new(SecurityModule::new()));
    conductor.register(Arc::new(StatsModule::new()));
    conductor.register(Arc::new(TunModule::new(TunConfig::default())));

    // The config file (AppState-shaped) is split by rsxm-config into opaque
    // per-module slices; the Conductor itself never reads any content.
    let envelope: ConfigEnvelope = match &rules_path {
        Some(path) => match std::fs::read_to_string(path) {
            Ok(text) => match serde_json::from_str::<serde_json::Value>(&text) {
                Ok(doc) => {
                    // A bare rules array is wrapped so the splitter sees it.
                    let doc = if doc.is_array() {
                        serde_json::json!({ "routeRules": doc })
                    } else {
                        doc
                    };
                    rsxm_config::split(&doc)
                }
                Err(err) => {
                    eprintln!("config parse error: {err}");
                    std::process::exit(1);
                }
            },
            Err(err) => {
                eprintln!("cannot read config file: {err}");
                std::process::exit(1);
            }
        },
        None => ConfigEnvelope::default(),
    };
    conductor.distribute(envelope);

    // Surface what each leader said about its slice before starting.
    for report in conductor.drain_reports() {
        println!(
            "{:>15} [{}] {}",
            report.module,
            report.kind.as_str(),
            report.message
        );
    }

    let report = conductor.start_all();
    for (name, result) in &report {
        match result {
            Ok(()) => println!("module {name}: started"),
            Err(err) => println!("module {name}: FAILED ({err})"),
        }
    }
    // Start reports land during start_all; drain them too.
    for report in conductor.drain_reports() {
        println!(
            "{:>15} [{}] {}",
            report.module,
            report.kind.as_str(),
            report.message
        );
    }

    let probe = route_domain.clone().or_else(|| route_ip.clone());
    if let Some(domain) = probe {
        let query = Query {
            domain: route_domain.clone(),
            package: route_package,
            destination_ip: route_ip.and_then(|s| s.parse().ok()),
            port: route_port,
            network: route_network,
            ..Default::default()
        };
        // The hot path takes a direct typed handle to the rules leader's
        // compiled table — the Conductor is never on the decision path.
        match rules_module
            .table()
            .and_then(|table| table.match_query(&query))
        {
            Some(m) => println!("route {domain} -> {:?} (rule {})", m.target, m.rule_id),
            None => println!("route {domain} -> no match"),
        }
    } else {
        for (name, health) in conductor.health() {
            let status = match health {
                Health::Up => "up".to_string(),
                other => other.to_string(),
            };
            println!("health {name}: {status}");
        }
    }

    conductor.stop_all();
}
