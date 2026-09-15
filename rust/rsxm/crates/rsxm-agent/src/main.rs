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

use rsxm_core::{Health, Query, Rule, Scheduler};
use rsxm_tun::{TunConfig, TunModule};
use std::sync::Arc;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut rules_path: Option<String> = None;
    let mut route_domain: Option<String> = None;
    let mut route_package: Option<String> = None;
    let mut route_ip: Option<String> = None;

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
            other => {
                eprintln!("unknown argument: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }

    let mut scheduler = Scheduler::new();
    scheduler.register(Arc::new(TunModule::new(TunConfig::default())));

    // Load rules if given; a missing file is fine for the health check mode.
    let rules: Vec<Rule> = match &rules_path {
        Some(path) => match std::fs::read_to_string(path) {
            Ok(text) => match serde_json::from_str(&text) {
                Ok(parsed) => parsed,
                Err(err) => {
                    eprintln!("rules parse error: {err}");
                    std::process::exit(1);
                }
            },
            Err(err) => {
                eprintln!("cannot read rules file: {err}");
                std::process::exit(1);
            }
        },
        None => Vec::new(),
    };
    scheduler.install_rules(rules);

    let report = scheduler.start_all();
    for (name, result) in &report {
        match result {
            Ok(()) => println!("module {name}: started"),
            Err(err) => println!("module {name}: FAILED ({err})"),
        }
    }

    let probe = route_domain.clone().or_else(|| route_ip.clone());
    if let Some(domain) = probe {
        let query = Query {
            domain: route_domain.clone(),
            package: route_package,
            destination_ip: route_ip.and_then(|s| s.parse().ok()),
            ..Default::default()
        };
        match scheduler.route(&query) {
            Some(m) => println!("route {domain} -> {:?} (rule {})", m.target, m.rule_id),
            None => println!("route {domain} -> no match"),
        }
    } else {
        for (name, health) in scheduler.health() {
            let status = match health {
                Health::Up => "up".to_string(),
                other => other.to_string(),
            };
            println!("health {name}: {status}");
        }
    }

    scheduler.stop_all();
}
