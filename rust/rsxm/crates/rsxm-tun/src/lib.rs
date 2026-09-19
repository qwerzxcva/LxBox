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

use rsxm_core::Health;

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
    /// Loopback port of the rsxm SOCKS5 server HEV dials into.
    pub socks_port: u16,
}

impl Default for TunConfig {
    fn default() -> Self {
        Self {
            mtu: 8500,
            ipv4: "198.18.0.1".into(),
            ipv6: None,
            icmp: false,
            socks_port: 7891,
        }
    }
}

/// The tun micro-kernel handle.
pub struct TunModule {
    config: std::sync::RwLock<TunConfig>,
    running: std::sync::atomic::AtomicBool,
    /// The tun fd handed over by Android, consumed by HEV at start.
    tun_fd: std::sync::RwLock<Option<i32>>,
    /// Join handle for the HEV main thread.
    hev_thread: std::sync::RwLock<Option<std::thread::JoinHandle<()>>>,
}

impl TunModule {
    pub fn new(config: TunConfig) -> Self {
        Self {
            config: std::sync::RwLock::new(config),
            running: std::sync::atomic::AtomicBool::new(false),
            tun_fd: std::sync::RwLock::new(None),
            hev_thread: std::sync::RwLock::new(None),
        }
    }

    /// Android hands the tunnel fd over before [rsxm_core::Module::start].
    pub fn set_tun_fd(&self, fd: i32) {
        *self
            .tun_fd
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(fd);
    }

    /// The loopback port of the rsxm SOCKS5 server (HEV's upstream).
    pub fn socks_port(&self) -> u16 {
        self.config
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .socks_port
    }

    pub fn config(&self) -> std::sync::RwLockReadGuard<'_, TunConfig> {
        self.config
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Renders the engine configuration as YAML for the native layer.
    pub fn render_engine_config(&self) -> String {
        let cfg = self
            .config
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
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
        // HEV dials the rsxm SOCKS5 server — the data path's only hop.
        yaml.push_str("socks5:\n");
        yaml.push_str(&format!("  port: {}\n", cfg.socks_port));
        yaml.push_str("  address: 127.0.0.1\n");
        yaml.push_str("misc:\n");
        yaml.push_str("  log-level: warn\n");
        yaml
    }
}

impl rsxm_core::Module for TunModule {
    fn name(&self) -> &'static str {
        "rsxm-tun"
    }

    fn depends_on(&self) -> &'static [&'static str] {
        // Packets cannot leave until the dial plan exists.
        &["rsxm-dialer"]
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
        if let Some(port) = value.get("rsxmSocksPort").and_then(|v| v.as_u64()) {
            cfg.socks_port = port as u16;
        }
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        let fd = self
            .tun_fd
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .ok_or("tun fd not provided")?;
        let yaml =
            std::ffi::CString::new(self.render_engine_config()).map_err(|_| "yaml contains NUL")?;
        let yaml_len = yaml.as_bytes().len() as u32;
        let yaml_ptr = yaml.as_ptr();
        let lib = unsafe { libloading::Library::new("libhev_tun.so") }
            .map_err(|_| "libhev_tun.so not found")?;
        // SAFETY: the symbol is resolved from the just-loaded library; the
        // Library handle is leaked so the symbols outlive the call — one
        // handle per process is exactly what a tunnel is. The yaml CString
        // is leaked too (one-shot per tunnel lifetime); the raw pointer is
        // Send as a plain usize.
        let main_from_str = unsafe {
            *lib.get::<unsafe extern "C" fn(*const u8, u32, i32) -> i32>(
                b"hev_socks5_tunnel_main_from_str",
            )
            .map_err(|e| format!("hev main symbol: {e}"))?
        };
        std::mem::forget(lib);
        let yaml_addr = yaml_ptr as usize;
        let handle = std::thread::spawn(move || {
            // SAFETY: see above — leaked yaml outlives the call.
            unsafe { main_from_str(yaml_addr as *const u8, yaml_len, fd) };
        });
        *self
            .hev_thread
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(handle);
        self.running
            .store(true, std::sync::atomic::Ordering::Release);
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        // SAFETY: dlopen/dlsym of the shipped lib; quit() is the same ABI
        // as the main call above.
        unsafe {
            if let Ok(lib) = libloading::Library::new("libhev_tun.so") {
                if let Ok(quit) = lib.get::<unsafe extern "C" fn()>(b"hev_socks5_tunnel_quit") {
                    quit();
                }
            }
        }
        if let Some(handle) = self
            .hev_thread
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take()
        {
            let _ = handle.join();
        }
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
    use rsxm_core::Module;

    #[test]
    fn lifecycle_requires_a_tun_fd() {
        let module = TunModule::new(TunConfig::default());
        assert!(matches!(module.health(), Health::Down(_)));
        // No fd handed over: start must fail with the explicit reason (and
        // not touch HEV at all).
        let err = module.start().unwrap_err();
        assert!(err.contains("tun fd not provided"));
        assert!(matches!(module.health(), Health::Down(_)));
    }

    #[test]
    fn yaml_points_hev_at_the_rsxm_socks_server() {
        let module = TunModule::new(TunConfig::default());
        let yaml = module.render_engine_config();
        assert!(yaml.contains("socks5:"));
        assert!(yaml.contains("port: 7891"));
        assert!(yaml.contains("address: 127.0.0.1"));
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
