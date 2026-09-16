fn main() {
    let path = std::env::args().nth(1).expect("usage: probe_split <appstate.json>");
    let doc = std::fs::read_to_string(path).unwrap();
    let doc: serde_json::Value = serde_json::from_str(&doc).unwrap();
    let env = rsxm_config::split(&doc);
    for s in env.slices() {
        println!("=== {} ===", s.module);
        println!("{}", serde_json::to_string_pretty(&*s.value).unwrap());
    }
}
