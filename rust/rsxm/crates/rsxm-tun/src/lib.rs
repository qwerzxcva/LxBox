//! RSXM tun module — the packet-I/O micro-kernel.
//!
//! ## Design decision: HEV as the packet engine, not as a SOCKS client
//! The existing HEV integration pipes the tun fd into a loopback SOCKS
//! server, which routes everything through sing-box's stack for decisions.
//! RSXM inverts that: HEV (lwIP + the task scheduler) becomes the *packet
//! engine* — it reads/writes the tun fd with its GSO-capable batched paths
//! — and hands each parsed flow to the scheduler's router directly. No
//! SOCKS hop, no double stack, no per-flow handshake to a local port.
//!
//! This module owns the abstraction; the FFI binding to libhev lands with
//! the runtime crate. Keeping it a separate micro-kernel means a future
//! swap to a native Rust stack is a module replacement, not a rewrite.

use rsxm_core::{Health, Module};

/// Configuration for the tun engine.
#[derive(Debug, Clone)]
pub struct TunConfig {
    /// Interface MTU. LwIP works best with a high MTU (HEV default 8500).
    pub mtu: u32,
    /// IPv4 address claimed on the interface.
    pub ipv4: String,
    /// Optional IPv6 address.
    pub ipv6: Option<String>,
    /// Enable ICMP echo replies (ping through the tunnel).
    pub icmp: bool,
}

impl Default for TunConfig {
    fn default() -> Self {
        Self {
            mtu: 8500,
            ipv4: "198.18.0.1".into(),
            ipv6: None,
            icmp: false,
        }
    }
}

/// The tun micro-kernel handle.
pub struct TunModule {
    config: std::sync::RwLock<TunConfig>,
    running: std::sync::atomic::AtomicBool,
}

impl TunModule {
    pub fn new(config: TunConfig) -> Self {
        Self {
            config: std::sync::RwLock::new(config),
            running: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn config(&self) -> std::sync::RwLockReadGuard<'_, TunConfig> {
        self.config.read().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Renders the engine configuration as YAML for the native layer.
    pub fn render_engine_config(&self) -> String {
        let cfg = self.config.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut yaml = String::new();
        yaml.push_str("tunnel:\n");
        yaml.push_str(&format!("  mtu: {}\n", cfg.mtu));
        yaml.push_str(&format!("  ipv4: {}\n", cfg.ipv4));
        if let Some(v6) = &cfg.ipv6 {
            yaml.push_str(&format!("  ipv6: '{v6}'\n"));
        }
        yaml.push_str(&format!(
            "  icmp: '{}'\n",
            if cfg.icmp { "reply" } else { "off" }
        ));
        yaml
    }
}

impl rsxm_core::Module for TunModule {
    fn name(&self) -> &'static str {
        "tun"
    }

    /// TUN 参数归本微内核所有：切片里有什么就更新什么，中央内核不读内容。
    fn configure(&self, slice: Option<&rsxm_core::ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            return Ok(());
        };
        let value = slice.value.as_object().ok_or("tun slice must be object")?;
        let mut cfg = self
            .config
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(mtu) = value.get("tunMtu").and_then(|v| v.as_u64()) {
            cfg.mtu = mtu as u32;
        }
        if let Some(v4) = value.get("tunInet4Address").and_then(|v| v.as_str()) {
            cfg.ipv4 = v4.to_string();
        }
        if let Some(v6) = value.get("tunInet6Address").and_then(|v| v.as_str()) {
            cfg.ipv6 = Some(v6.to_string());
        }
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        // The runtime crate binds this to the native engine's start; the
        // module itself only tracks lifecycle during the migration.
        self.running
            .store(true, std::sync::atomic::Ordering::Release);
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        self.running
            .store(false, std::sync::atomic::Ordering::Release);
        Ok(())
    }

    fn health(&self) -> Health {
        if self.running.load(std::sync::atomic::Ordering::Acquire) {
            Health::Up
        } else {
            Health::Down("not started")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lifecycle_tracks_health() {
        let module = TunModule::new(TunConfig::default());
        assert!(matches!(module.health(), Health::Down(_)));
        module.start().unwrap();
        assert_eq!(module.health(), Health::Up);
        module.stop().unwrap();
        assert!(matches!(module.health(), Health::Down(_)));
    }

    #[test]
    fn engine_config_renders_yaml_shape() {
        let module = TunModule::new(TunConfig {
            ipv6: Some("fc00::1".into()),
            ..Default::default()
        });
        let yaml = module.render_engine_config();
        assert!(yaml.contains("mtu: 8500"));
        assert!(yaml.contains("ipv4: 198.18.0.1"));
        assert!(yaml.contains("ipv6: 'fc00::1'"));
        assert!(yaml.contains("icmp: 'off'"));
    }
}
