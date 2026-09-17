//! Thread-per-module runner — each micro-kernel gets its own OS thread.
//!
//! Design borrowed from mihomo: every goroutine lives in its own OS thread,
//! modules never block each other, and the boss (Conductor) stays a pure
//! coordinator. Android ART / Bionic handle thread creation cheaply on
//! modern Qualcomm cores, so the per-module overhead (~20 KB stack) is
//! negligible; what matters is that a DNS cache miss on one thread cannot
//! stall route matching on another.
//!
//! ```text
//!  Conductor ──Cmd──▶ ModuleRunner (thread 1) ──▶ rsxm-rules
//!        │                  ▲
//!        │◀── Report ───────┘
//!        │
//!  Conductor ──Cmd──▶ ModuleRunner (thread 2) ──▶ rsxm-dns
//!        │                  ▲
//!        │◀── Report ───────┘
//! ```
//!
//! ## Why std::thread, not tokio
//!
//! The target is Android's Bionic libc, where `pthread_create` is cheap and
//! the ART runtime already allocates 10–20 threads for the VM itself.
//! Pulling a tokio runtime into the core crate would add ~1 MB of release
//! size for a feature that gains us nothing on a 2-thread-per-module
//! ceiling. Pure std is also deterministically schedulable — the kernel's
//! own priorities (SCHED_FIFO for the VPN thread, SCHED_OTHER for workers)
//! are what actually determine latency.

use std::sync::mpsc::{Receiver, Sender};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::{ConfigSlice, Module, Reporter};

// ---------------------------------------------------------------------------
// Command protocol — what the boss sends to a module thread
// ---------------------------------------------------------------------------

/// Order from the Conductor to one module. Commands are serialized per
/// module (one thread per module) so `Configure` never races `Start`.
#[derive(Debug)]
pub enum Cmd {
    /// Hand the module a new config slice (or none = feature disabled).
    Configure(Option<ConfigSlice>),
    /// Bring the module up. Idempotent — a module that is already up returns Ok.
    Start,
    /// Ask the module to stop. Must be idempotent and quick.
    Stop,
    /// Shut down the runner thread entirely (drop the module handle).
    Shutdown,
}

/// Result of a command the Conductor waited on.
#[derive(Debug, Clone)]
pub enum CmdResult {
    /// The command succeeded.
    Ok,
    /// The module reported an error; the string is the module's message.
    Err(String),
    /// The runner thread exited (panic or Shutdown).
    Done,
}

// ---------------------------------------------------------------------------
// ModuleRunner — one per module, owns the thread
// ---------------------------------------------------------------------------

/// A module running in its own thread. The [`ModuleRunner`] handle is
/// `Send` + `Sync` so the Conductor can dispatch commands from any thread
/// (the Android main thread, the VPN service thread, a JNI callback).
pub struct ModuleRunner {
    name: &'static str,
    cmd_tx: Sender<Envelope>,
    thread: Option<JoinHandle<()>>,
    /// Whether the thread has been told to shut down.
    shut: Arc<Mutex<bool>>,
    /// Tracks the running state — hot path reads this to decide whether a
    /// `Configure` should cycle the module inline.
    running: Arc<AtomicUsize>,
}

/// A `Cmd` bundled with a one-shot reply channel so the Conductor can
/// synchronously wait for the result.
struct Envelope {
    cmd: Cmd,
    reply: Option<Sender<CmdResult>>,
}

impl ModuleRunner {
    /// Spawns a new thread that owns `module` and processes commands from
    /// the Conductor. The thread attaches the reporter to the module once
    /// before entering its command loop — `module.start()` is never called
    /// on the caller's thread.
    pub fn spawn(
        name: &'static str,
        module: Arc<dyn Module>,
        reporter: Reporter,
    ) -> Self {
        let (cmd_tx, cmd_rx) = std::sync::mpsc::channel::<Envelope>();
        let shut = Arc::new(Mutex::new(false));
        let running = Arc::new(AtomicUsize::new(0));
        let thread_shut = shut.clone();
        let thread_running = running.clone();

        let handle = thread::Builder::new()
            .name(format!("rsxm-{name}"))
            // 256 KB is plenty for a state-machine loop that delegates all
            // real work to heap-allocated buffers. The default 2 MB on Android
            // is wasteful when we can have 10+ modules in a box.
            .stack_size(256 * 1024)
            .spawn(move || run_loop(module, reporter, cmd_rx, thread_shut, thread_running))
            .expect("failed to spawn module thread");

        Self {
            name,
            cmd_tx,
            thread: Some(handle),
            shut,
            running,
        }
    }

    /// Synchronously dispatches a command and waits up to `timeout` for the
    /// module's result. Returns `CmdResult::Done` if the thread exits first.
    pub fn dispatch(&self, cmd: Cmd, timeout: Duration) -> CmdResult {
        let (reply_tx, reply_rx) = std::sync::mpsc::channel();
        let _ = self.cmd_tx.send(Envelope { cmd, reply: Some(reply_tx) });
        // A Shutdown reply might never arrive (thread joins immediately);
        // that's fine — the caller usually just wants fire-and-forget for
        // that one.
        match reply_rx.recv_timeout(timeout) {
            Ok(r) => r,
            Err(_) => CmdResult::Done,
        }
    }

    /// Fire-and-forget: send a command without waiting for a result.
    pub fn fire(&self, cmd: Cmd) {
        let _ = self.cmd_tx.send(Envelope { cmd, reply: None });
    }

    pub fn name(&self) -> &'static str {
        self.name
    }

    /// Whether the runner currently considers the module running.
    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Acquire) != 0
    }

    /// Gracefully shut down the module thread. Returns once the thread has
    /// joined or `timeout` elapsed (whichever first). Idempotent.
    pub fn join(mut self, timeout: Duration) {
        {
            let mut guard = self.shut.lock().unwrap();
            if *guard {
                return;
            }
            *guard = true;
        }
        // Synchronous Shutdown so we know the module actually stopped before
        // the thread is allowed to exit — avoids a race where the Conductor
        // moves on to the next start_all while the old thread is still
        // draining its final queue.
        let _ = self.dispatch(Cmd::Shutdown, timeout);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

// ---------------------------------------------------------------------------
// The per-thread run loop — pure state machine, no I/O
// ---------------------------------------------------------------------------

fn run_loop(
    module: Arc<dyn Module>,
    reporter: Reporter,
    rx: Receiver<Envelope>,
    shut: Arc<Mutex<bool>>,
    running_flag: Arc<AtomicUsize>,
) {
    let name = module.name();
    let mreport = reporter.for_module(name);

    // Attach happens once per thread lifetime; the module stores the handle.
    module.attach(&mreport);

    // Previous configuration slice — retained only so a Configure arriving
    // before any Start can be kept as the current baseline. We never read it
    // directly here (the Conductor is the source of truth), but assigning it
    // keeps the intent visible to future readers.
    #[allow(dead_code)]
    let mut _current_slice: Option<ConfigSlice> = None;
    let mut running = false;

    // Drain any pre-configure commands that may have queued before the
    // Conductor's first distribute() call. Unbounded recv would block here;
    // we check shut first to keep shutdown responsive.
    loop {
        // Don't sleep forever — the thread must notice shutdown promptly.
        let envelope = match rx.recv_timeout(Duration::from_secs(30)) {
            Ok(e) => e,
            Err(_) => {
                // Timeout: check for shutdown, then go back to waiting.
                if *shut.lock().unwrap() {
                    break;
                }
                continue;
            }
        };

        // Shutdown is the only command that breaks the loop.
        if matches!(envelope.cmd, Cmd::Shutdown) {
            let _ = module.stop();
            running_flag.store(0, Ordering::Release);
            mreport.info("module thread exiting");
            if let Some(reply) = envelope.reply {
                let _ = reply.send(CmdResult::Ok);
            }
            break;
        }

        let send = |reply: Option<Sender<CmdResult>>, r: CmdResult| {
            if let Some(tx) = reply {
                let _ = tx.send(r);
            }
        };

        match envelope.cmd {
            Cmd::Configure(slice) => {
                _current_slice = slice.clone();
                let result = module.configure(slice.as_ref());
                match &result {
                    Ok(()) => mreport.info("configured"),
                    Err(why) => mreport.error(format!("configure failed: {why}")),
                }
                // If we're already running and receive a new config, cycle
                // the module so it picks up the changes — cheap for
                // stateless modules; heavyweight ones can keep old state.
                if running {
                    let reconf = module.reconfigure(slice.as_ref());
                    let final_r = match reconf {
                        Ok(()) => {
                            mreport.info("reconfigured in-place");
                            CmdResult::Ok
                        }
                        Err(why) => {
                            mreport.error(format!("reconfigure failed: {why}"));
                            CmdResult::Err(why)
                        }
                    };
                    send(envelope.reply, final_r);
                    continue;
                }
                let r = match result {
                    Ok(()) => CmdResult::Ok,
                    Err(why) => CmdResult::Err(why),
                };
                send(envelope.reply, r);
            }
            Cmd::Start => {
                if running {
                    mreport.info("already running");
                    send(envelope.reply, CmdResult::Ok);
                    continue;
                }
                let result = module.start();
                match &result {
                    Ok(()) => {
                        running = true;
                        running_flag.store(1, Ordering::Release);
                        mreport.info("module started");
                    }
                    Err(why) => mreport.error(format!("start failed: {why}")),
                }
                let r = match result {
                    Ok(()) => CmdResult::Ok,
                    Err(why) => CmdResult::Err(why),
                };
                send(envelope.reply, r);
            }
            Cmd::Stop => {
                if !running {
                    send(envelope.reply, CmdResult::Ok);
                    continue;
                }
                let result = module.stop();
                running = false;
                running_flag.store(0, Ordering::Release);
                match &result {
                    Ok(()) => mreport.info("module stopped"),
                    Err(why) => mreport.error(format!("stop error: {why}")),
                }
                let r = match result {
                    Ok(()) => CmdResult::Ok,
                    Err(why) => CmdResult::Err(why),
                };
                send(envelope.reply, r);
            }
            Cmd::Shutdown => unreachable!(),
        }
    }
}

// ---------------------------------------------------------------------------
// ThreadedConductor — a Conductor that runs modules in their own threads
// ---------------------------------------------------------------------------

/// Like [`Conductor`] but each registered module gets its own OS thread.
///
/// The Conductor's API is unchanged (register / distribute / start_all /
/// stop_all); what changes is where the work happens. Useful when you want
/// isolation between a long-running module (TUN, TLS dialer) and the rest
/// of the kernel (DNS, rules matching).
///
/// ## Caveats on Android
///
///  - Thread creation is cheap (~50 µs on Snapdragon 8 Gen 5) but each
///    thread costs a fixed ~200 KB of stack+metadata; keep modules under
///    10–12 or you'll feel the pressure.
///  - All threads share the ART's scheduler priority; the VPN service
///    thread runs at SCHED_FIFO via `android.os.Process.setThreadPriority()`,
///    so packet-path modules should never be spawned here — they should
///    run inline on the VPN service's IO threads.
///
/// For those reasons, `ThreadedConductor` is for control-plane modules only:
/// `rsxm-power`, `rsxm-security`, `rsxm-sniffer`. Packet-path modules stay
/// on the inline `Conductor`.
pub struct ThreadedConductor {
    reporter: Reporter,
    runners: Vec<ModuleRunner>,
}

impl ThreadedConductor {
    pub fn new(reporter: Reporter) -> Self {
        Self {
            reporter,
            runners: Vec::new(),
        }
    }

    pub fn reporter(&self) -> Reporter {
        self.reporter.clone()
    }

    /// Registers `module` and spawns its thread immediately.
    pub fn register(&mut self, module: Arc<dyn Module>) {
        let name = module.name();
        let runner = ModuleRunner::spawn(name, module, self.reporter.clone());
        self.runners.push(runner);
    }

    /// Sends a config slice to every registered module. Hot-configure
    /// modules absorb it in-place; others get cycle-triggered on their
    /// runner thread. Synchronous — returns when every runner has ACKed.
    pub fn distribute(&self, envelope: crate::ConfigEnvelope) -> Vec<(&'static str, CmdResult)> {
        let mut outcomes = Vec::new();
        for runner in &self.runners {
            let slice = envelope.for_module(runner.name()).cloned();
            let result = runner.dispatch(Cmd::Configure(slice), Duration::from_secs(2));
            outcomes.push((runner.name(), result));
        }
        outcomes
    }

    /// Sends `Start` to every module (dependency order is the caller's job).
    /// Synchronous.
    pub fn start_all(&self) -> Vec<(&'static str, CmdResult)> {
        let mut outcomes = Vec::new();
        for runner in &self.runners {
            let result = runner.dispatch(Cmd::Start, Duration::from_secs(2));
            outcomes.push((runner.name(), result));
        }
        outcomes
    }

    /// Sends `Stop` then `Shutdown` to every module. Consumes self.
    pub fn shutdown(self) {
        for runner in self.runners {
            runner.join(Duration::from_secs(5));
        }
    }
}

impl Default for ThreadedConductor {
    fn default() -> Self {
        Self::new(Reporter::new(2048))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Module;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Mutex;

    struct ThreadTestModule {
        name: &'static str,
        started: AtomicUsize,
        stopped: AtomicUsize,
        last_thread: Mutex<Option<thread::ThreadId>>,
    }

    impl ThreadTestModule {
        fn new(name: &'static str) -> Arc<Self> {
            Arc::new(Self {
                name,
                started: AtomicUsize::new(0),
                stopped: AtomicUsize::new(0),
                last_thread: Mutex::new(None),
            })
        }
    }

    impl Module for ThreadTestModule {
        fn name(&self) -> &'static str {
            self.name
        }
        fn configure(&self, _slice: Option<&ConfigSlice>) -> Result<(), String> {
            Ok(())
        }
        fn start(&self) -> Result<(), String> {
            self.started.fetch_add(1, Ordering::SeqCst);
            *self.last_thread.lock().unwrap() = Some(thread::current().id());
            Ok(())
        }
        fn stop(&self) -> Result<(), String> {
            self.stopped.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }

    #[test]
    fn each_module_runs_on_its_own_thread() {
        let reporter = Reporter::new(64);
        let mut tc = ThreadedConductor::new(reporter.clone());
        let a = ThreadTestModule::new("a");
        let b = ThreadTestModule::new("b");
        let a_clone = a.clone();
        let b_clone = b.clone();
        tc.register(a);
        tc.register(b);
        tc.start_all();
        // Give both threads time to pick up Start.
        thread::sleep(Duration::from_millis(50));

        let thread_a = a_clone.last_thread.lock().unwrap().unwrap();
        let thread_b = b_clone.last_thread.lock().unwrap().unwrap();
        assert_ne!(thread_a, thread_b, "each module must get its own thread");
        assert_ne!(
            thread_a,
            thread::current().id(),
            "module must NOT run on the conductor thread"
        );
        assert_eq!(a_clone.started.load(Ordering::SeqCst), 1);
        assert_eq!(b_clone.started.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn distribute_configures_all_modules() {
        let reporter = Reporter::new(64);
        let mut tc = ThreadedConductor::new(reporter);
        let m = ThreadTestModule::new("cfg");
        let m_clone = m.clone();
        tc.register(m);
        let env = crate::ConfigEnvelope::new(vec![ConfigSlice::new("cfg", serde_json::json!({}))]);
        tc.distribute(env);
        tc.start_all();
        thread::sleep(Duration::from_millis(50));
        assert_eq!(m_clone.started.load(Ordering::SeqCst), 1);
        // A second distribute while running triggers stop+start → 2 starts.
        tc.distribute(crate::ConfigEnvelope::new(vec![ConfigSlice::new("cfg", serde_json::json!({"v":2}))]));
        thread::sleep(Duration::from_millis(50));
        assert_eq!(m_clone.started.load(Ordering::SeqCst), 2);
        assert_eq!(m_clone.stopped.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn shutdown_joins_all_threads() {
        let reporter = Reporter::new(64);
        let mut tc = ThreadedConductor::new(reporter);
        let m = ThreadTestModule::new("down");
        let m_clone = m.clone();
        tc.register(m);
        tc.start_all();
        thread::sleep(Duration::from_millis(30));
        tc.shutdown();
        assert_eq!(m_clone.stopped.load(Ordering::SeqCst), 1);
    }
}
