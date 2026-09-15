//! JNI entry points called from Kotlin (System.loadLibrary("aibox_core")).
//!
//! The Kotlin side passes the connection snapshot as JSON (the exact
//! BoxEngine.ConnectionInfo shape) and gets back "[<fingerprint>,<json>]" —
//! the service splits on the first comma and skips the broadcast when the
//! fingerprint matches the previous push.

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
    .unwrap_or_else(|e| format!("[0,{{}}]"));

    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
